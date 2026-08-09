package com.sherva.voiceassistant.asr

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.sherva.voiceassistant.AppLog
import com.sherva.voiceassistant.ModelPaths
import com.sherva.voiceassistant.audio.SpeechEnhancer
import kotlin.concurrent.thread

/**
 * 流式 ASR 引擎：封装 OnlineRecognizer（streaming-zipformer-bilingual-zh-en int8）。
 *
 * ★ 这是官方 streaming demo 的核心模式，体验远优于离线方案：
 *   - 边说边出字（partial result 实时回调）
 *   - 结果连续纠正（随着你继续说，前面识别的字会不断被修正）
 *   - 内置端点检测（EndpointConfig），不需要单独的 silero VAD
 *
 * 工作流程（内部线程）：
 *   录音(16kHz) → stream.acceptWaveform → 循环 decode → getResult(partial)
 *              → isEndpoint? → reset(开始新一句) → onFinal
 *
 * 端点参数说明（EndpointRule）：
 *   rule1: 说话中静默 >2.4s → 端点
 *   rule2: 有语音且尾静默 >1.4s → 端点（主要用这个）
 *   rule3: 单句累计 >20s → 强制端点
 */
class StreamingAsrEngine(
    context: Context,
    private val numThreads: Int = 4,
    /** 端点尾静默(s)：说完停顿多久判定该句结束。 */
    endpointTrailingSilenceSec: Float = 1.2f,
    /** ★ 麦克风软件增益（1.0=原始，>1 放大让远距离也能识别）。 */
    private val micGain: Float = 1.0f,
) {
    private val recognizer = run {
        AppLog.i("SASR", "构造 OnlineRecognizer: encoder=${ModelPaths.STREAM_ENCODER}")
        OnlineRecognizer(
            assetManager = context.assets,
            config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = ModelPaths.STREAM_ENCODER,
                        decoder = ModelPaths.STREAM_DECODER,
                        joiner = ModelPaths.STREAM_JOINER,
                    ),
                    tokens = ModelPaths.STREAM_TOKENS,
                    numThreads = numThreads,
                    provider = "cpu",
                    modelType = "zipformer",
                ),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0.0f),
                    rule2 = EndpointRule(true, endpointTrailingSilenceSec, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, 20.0f),
                ),
                enableEndpoint = true,
            )
        ).also { AppLog.i("SASR", "OnlineRecognizer 构造成功") }
    }
    private val denoiser = SpeechEnhancer(context)

    private var record: AudioRecord? = null
    private var stream: OnlineStream? = null
    @Volatile private var running = false
    private var workThread: Thread? = null

    /**
     * 启动流式识别（含录音）。
     * @param onPartial 每次解码后的实时文本（会连续纠正更新），UI 直接覆盖显示即可
     * @param onFinal  端点检测命中：一句话说完的最终文本
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit) {
        if (running) return
        val sampleRate = 16000
        // 每次重新聆听前重置降噪器状态，清除上一轮（含打断）的残留回声缓冲
        denoiser.reset()
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
        stream = recognizer.createStream()
        record!!.startRecording()
        running = true
        AppLog.i("SASR", "流式识别已启动")

        workThread = thread(true, name = "streaming-asr") {
            val intervalSamples = (0.1 * sampleRate).toInt()  // 每 100ms 处理一次
            val buf = ShortArray(intervalSamples)
            try {
                while (running) {
                    val n = record!!.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    // ★ 软件增益：放大采样值，让远距离/小声说话也能识别
                    val raw = FloatArray(n) { (buf[it] / 32768.0f) * micGain }
                    // 钳制到 [-1,1]，防削波失真
                    for (i in raw.indices) {
                        if (raw[i] > 1f) raw[i] = 1f
                        else if (raw[i] < -1f) raw[i] = -1f
                    }
                    // ★ GTCRN 实时降噪后再喂 ASR（提精度 + 消 TTS 回声污染）
                    val clean = denoiser.process(raw, sampleRate)
                    if (clean.isEmpty()) continue
                    val st = stream ?: break
                    st.acceptWaveform(clean, sampleRate)
                    while (recognizer.isReady(st)) recognizer.decode(st)

                    val text = recognizer.getResult(st).text
                    if (text.isNotBlank()) onPartial(text)

                    if (recognizer.isEndpoint(st)) {
                        val finalText = text.trim()
                        recognizer.reset(st)
                        if (finalText.isNotEmpty()) {
                            AppLog.i("SASR", "端点命中，final=\"$finalText\"")
                            onFinal(finalText)
                        }
                    }
                }
            } catch (e: Throwable) {
                AppLog.e("SASR", "流式识别线程异常", e)
            } finally {
                AppLog.i("SASR", "流式识别线程结束")
            }
        }
    }

    /** 停止识别与录音。 */
    fun stop() {
        running = false
        workThread?.join(300)
        workThread = null
        try { record?.stop() } catch (_: Throwable) {}
        record?.release()
        record = null
        runCatching { stream?.release() }
        stream = null
    }

    fun release() {
        stop()
        recognizer.release()
        denoiser.release()
    }
}
