package com.sherva.voiceassistant.asr

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.sherva.voiceassistant.AppLog
import com.sherva.voiceassistant.ModelPaths
import com.sherva.voiceassistant.audio.SpeechEnhancer
import kotlin.concurrent.thread

/**
 * 唤醒词检测引擎：封装 sherpa-onnx KeywordSpotter。
 *
 * ★ 与 StreamingAsrEngine 区别：
 *   - ASR 整句识别（输出流式文字）→ 5~15% CPU
 *   - KWS 极简检测（仅命中预设关键词）→ 1~3% CPU
 *
 * 工作流程（内部线程）：
 *   录音(16kHz) → GTCRN 降噪 → spotter.acceptWaveform
 *              → 循环 decode → getResult.keyword 非空 → 命中
 *
 * 关键词文件 `assets/keywords/wake_words.txt` 格式：
 *   每行：拼音 token 序列 @中文标签
 *   例：h āi s ài l ín n à @嗨赛琳娜
 *
 * 模型路径在 [ModelPaths.KWS_*]，由 build.gradle 配置（int8 + chunk-16 精度+延迟平衡）。
 */
class KeywordSpotterEngine(
    context: Context,
    private val keywordsFile: String = ModelPaths.KEYWORDS_FILE,
    private val numThreads: Int = 2,
    private val threshold: Float = 0.25f,
) {
    private val spotter: KeywordSpotter = run {
        AppLog.i("KWS", "构造 KeywordSpotter: model=${ModelPaths.KWS_ENCODER}, threshold=$threshold")
        KeywordSpotter(
            assetManager = context.assets,
            config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = ModelPaths.KWS_ENCODER,
                        decoder = ModelPaths.KWS_DECODER,
                        joiner = ModelPaths.KWS_JOINER,
                    ),
                    tokens = ModelPaths.KWS_TOKENS,
                    numThreads = numThreads,
                    provider = "cpu",
                    modelType = "zipformer",
                ),
                keywordsFile = keywordsFile,
                keywordsThreshold = threshold,
                keywordsScore = 1.0f,
                numTrailingBlanks = 1,
                maxActivePaths = 4,
            )
        ).also { AppLog.i("KWS", "KeywordSpotter 构造成功") }
    }
    private val denoiser = SpeechEnhancer(context)

    /** 当前活跃 stream（每次 start() 重建）。 */
    private var stream: OnlineStream? = null
    private var record: AudioRecord? = null
    @Volatile private var running = false
    private var workThread: Thread? = null

    /**
     * 启动 KWS 监听（含录音）。
     * @param onHit 关键词命中时回调，参数为关键词中文名（如 "嗨赛琳娜"）
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(onHit: (keyword: String) -> Unit) {
        if (running) return
        denoiser.reset()   // 清上一轮的残留缓冲
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufBytes = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            .coerceAtLeast(1600 * 2 * 4)

        @Suppress("MissingPermission")
        record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate, channelConfig, audioFormat, bufBytes
        )
        check(record?.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失败" }
        // createStream 接受 keyword 作为标识符；传空串使用默认（首条关键词）
        stream = spotter.createStream("")
        record!!.startRecording()
        running = true
        AppLog.i("KWS", "唤醒词检测已启动")

        workThread = thread(true, name = "kws-spotter") {
            val intervalSamples = (0.1 * sampleRate).toInt()   // 100ms
            val buf = ShortArray(intervalSamples)
            try {
                while (running) {
                    val n = record!!.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val raw = FloatArray(n) { buf[it] / 32768.0f }
                    // GTCRN 降噪（与 ASR 同一管线，避免 TTS 回声污染唤醒）
                    val clean = denoiser.process(raw, sampleRate)
                    if (clean.isEmpty()) continue
                    val st = stream ?: break
                    st.acceptWaveform(clean, sampleRate)
                    while (spotter.isReady(st)) {
                        spotter.decode(st)
                        val result = spotter.getResult(st)
                        val keyword = result.keyword
                        if (keyword.isNotBlank()) {
                            AppLog.i("KWS", "唤醒词命中: \"$keyword\"")
                            spotter.reset(st)   // 立即重置 stream，准备下一次检测
                            // 在主线程上回调
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                onHit(keyword)
                            }
                            // 命中后不停 KWS，让循环继续；用户说完唤醒词后会自然停
                        }
                    }
                }
            } catch (e: Throwable) {
                AppLog.e("KWS", "唤醒线程异常", e)
            } finally {
                AppLog.i("KWS", "唤醒线程结束")
            }
        }
    }

    /** 停止 KWS 监听与录音。 */
    fun stop() {
        running = false
        workThread?.join(300)
        workThread = null
        try { record?.stop() } catch (_: Throwable) {}
        record?.release()
        record = null
        runCatching { stream?.release() }
        stream = null
        runCatching { denoiser.flush() }   // 冲刷降噪缓冲
    }

    fun release() {
        stop()
        spotter.release()
        denoiser.release()
    }
}
