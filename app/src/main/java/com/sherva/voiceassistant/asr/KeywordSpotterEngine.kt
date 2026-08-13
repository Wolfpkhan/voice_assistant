package com.sherva.voiceassistant.asr

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import androidx.annotation.RequiresPermission
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.sherva.voiceassistant.AppLog
import com.sherva.voiceassistant.ModelPaths
import com.sherva.voiceassistant.audio.AecManager
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
    private val numThreads: Int = 1,
    /** 阈值：越低越灵敏（0.05 非常灵敏，0.1 较灵敏，0.25 默认偏高，0.5 几乎不命中）。 */
    private val threshold: Float = 0.0f,
) {
    private val spotter: KeywordSpotter = run {
        AppLog.i("KWS", "构造 KeywordSpotter: encoder=${ModelPaths.KWS_ENCODER}, threshold=$threshold")
        AppLog.i("KWS", "keywordsFile=$keywordsFile (绝对路径将传入)")
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
                    numThreads = 1,   // KWS 建议单线程（与 ASR 多线程不同）
                    provider = "cpu",
                    modelType = "zipformer2",  // sherpa-onnx KWS 模型用 zipformer2（与 ASR streaming-zipformer 不同）
                ),
                keywordsFile = keywordsFile,
                keywordsThreshold = threshold,
                keywordsScore = 3.0f,  // ★ 大幅 boost 关键词路径（默认 1.0）
                numTrailingBlanks = 0,  // ★ 0: 词后任意帧可触发; 默认 1 需要 2 帧静音
                maxActivePaths = 4,
            )
        ).also {
            AppLog.i("KWS", "KeywordSpotter 构造成功")
            // ★ 立即创建测试 stream，验证关键词被正确编码
            //   如果 OOV，stream.ptr 会返回 0
            val testStream = it.createStream("")
            if (testStream.ptr == 0L) {
                AppLog.e("KWS", "⚠ 关键词编码失败！检查 tokens.txt 是否包含关键词中所有 token")
            } else {
                AppLog.i("KWS", "测试 stream 创建成功（关键词编码 OK）")
                testStream.release()
            }
        }
    }
    private val appContext: Context = context.applicationContext
    private var aec: AcousticEchoCanceler? = null  // 系统硬件 AEC（聆听听筒扬声器声音用）

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
        // ★ 硬件 AEC：消除 TTS / 音乐播放时的扬声器回声，避免误唤醒
        aec = AecManager.enable(record!!)
        AppLog.i("KWS", "AudioRecord 实际采样率=${record!!.sampleRate} Hz, 请求=$sampleRate Hz")
        // ★ 关键：用 actual sample rate 喂入 acceptWaveform（Android 可能不同）
        val actualSampleRate = record!!.sampleRate
        // createStream 接受 keyword 作为标识符；传空串使用默认（首条关键词）
        stream = spotter.createStream("")
        record!!.startRecording()
        running = true
        AppLog.i("KWS", "唤醒词检测已启动")

        workThread = thread(true, name = "kws-spotter") {
            val intervalSamples = (0.1 * sampleRate).toInt()   // 100ms
            val buf = ShortArray(intervalSamples)
            var totalFrames = 0
            try {
                while (running) {
                    val n = record!!.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    // 直接用原始 PCM（不走 GTCRN 降噪，避免过度处理破坏 KWS 输入特征）
                    val raw = FloatArray(n) { buf[it] / 32768.0f }
                    val st = stream ?: break
                    st.acceptWaveform(raw, actualSampleRate)
                    totalFrames++
                    var decodeCount = 0
                    while (spotter.isReady(st)) {
                        spotter.decode(st)
                        decodeCount++
                        val result = spotter.getResult(st)
                        val keyword = result.keyword
                        if (keyword.isNotBlank()) {
                            AppLog.i("KWS", "唤醒词命中: \"$keyword\" (tokens=${result.tokens.size})")
                            spotter.reset(st)
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                onHit(keyword)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                AppLog.e("KWS", "唤醒线程异常", e)
            } finally {
                AppLog.i("KWS", "唤醒线程结束 (总帧=$totalFrames)")
            }
        }
    }

    /** 停止 KWS 监听与录音。 */
    fun stop() {
        running = false
        // ★ 关键顺序：先 stop record 让阻塞中的 read() 立刻返回，再 join worker 退出，最后再 release
        //   否则 join 超时后 record.release() 会导致 worker 线程在 spotter.decode() 中被掐断 → native crash
        try { record?.stop() } catch (_: Throwable) {}
        workThread?.join(1000)   // 延长到 1s，覆盖 ONNX 推理最坏耗时
        workThread = null
        record?.release()
        record = null
        runCatching { stream?.release() }
        stream = null
        AecManager.disable(aec)
        aec = null
    }

    fun release() {
        stop()
        spotter.release()
    }
}
