package com.sherva.voiceassistant.asr

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import androidx.annotation.RequiresPermission
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.sherva.voiceassistant.AppLog
import com.sherva.voiceassistant.ModelPaths
import com.sherva.voiceassistant.audio.AecManager
import kotlin.concurrent.thread

/** FloatArray 拼接（VAD 窗口累积用） */
private operator fun FloatArray.plus(other: FloatArray): FloatArray {
    val out = FloatArray(size + other.size)
    System.arraycopy(this, 0, out, 0, size)
    System.arraycopy(other, 0, out, size, other.size)
    return out
}

/**
 * QNN(HTP NPU) 语音识别引擎：silero VAD 端点 + 离线 paraformer QNN 滑窗模拟流式。
 *
 * 背景：官方没有「中文流式」QNN 模型，QNN 只有固定时长的离线包（本工程选 5 秒窗）。
 * 本引擎对齐官方 SimulateStreamingAsr demo 的思路，并保持与 StreamingAsrEngine
 * 相同的对外接口（[start]/[stop]/[resetStream]/[release]），供 VoiceAssistant 无缝切换：
 *
 *   录音(16kHz) → silero VAD 检测语音起止
 *     ├─ 说话中：每 [PARTIAL_INTERVAL_SEC] 对「最近 [ModelPaths.ASR_QNN_WINDOW_SEC] 秒」
 *     │          做一次离线识别 → onPartial（模拟流式出字，短窗会截尾属正常，final 会修正）
 *     └─ 端点命中（尾静默超时）：对完整语音段识别 → onFinal → 清空进入下一句
 *
 * 初始化失败（无 QNN 模型/无 libQnnHtp.so/芯片不支持）时，[AsrEngine] 内部自动
 * 回退 CPU 离线 paraformer，本引擎逻辑不变，只是推理跑 CPU。
 */
class QnnAsrEngine(
    context: Context,
    /** 端点尾静默(s)：说完停顿多久判定该句结束。 */
    private val endpointTrailingSilenceSec: Float = 1.2f,
    /** 麦克风软件增益（与 StreamingAsrEngine 一致）。 */
    private val micGain: Float = 1.0f,
    /** 全局回声消除（与 StreamingAsrEngine 一致）。 */
    private val globalAec: Boolean = false,
) : VoiceAsrEngine {

    private val appContext: Context = context.applicationContext

    /** 实际生效 provider："qnn" 或 "cpu"（QNN 初始化失败时回退） */
    val provider: String

    private val asr: AsrEngine
    private val vad: Vad

    init {
        asr = AsrEngine(appContext, useQnn = true)
        provider = asr.provider
        if (provider == "cpu") {
            AppLog.w(TAG, "QNN 未生效（已回退 CPU 离线识别），仍可正常使用")
        }
        // 尾静默端点用 VAD 的 minSilenceDuration 表达
        vad = Vad(
            assetManager = appContext.assets,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = ModelPaths.VAD_MODEL,
                    threshold = 0.7f,
                    minSilenceDuration = endpointTrailingSilenceSec,
                    minSpeechDuration = 0.4f,
                    windowSize = 512,
                    maxSpeechDuration = 15f,
                ),
                sampleRate = 16000,
                numThreads = 2,
                provider = "cpu",
                debug = false,
            )
        ).also { AppLog.i(TAG, "silero VAD 构造成功") }
    }

    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    @Volatile private var running = false
    private var workThread: Thread? = null
    @Volatile private var pendingReset = false

    override fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit) {
        if (running) return
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufBytes = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            .coerceAtLeast(1600 * 2 * 4)

        @Suppress("MissingPermission")
        val source = if (globalAec) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                     else MediaRecorder.AudioSource.VOICE_RECOGNITION
        record = AudioRecord(source, sampleRate, channelConfig, audioFormat, bufBytes)
        check(record?.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失败" }
        aec = AecManager.enable(record!!)
        record!!.startRecording()
        running = true
        AppLog.i(TAG, "QNN 识别已启动 (provider=$provider)")

        workThread = thread(true, name = "qnn-asr") {
            // —— 滑窗模拟流式状态 ——
            val windowSamples = (ModelPaths.ASR_QNN_WINDOW_SEC * sampleRate).toInt()
            val partialIntervalSamples = (PARTIAL_INTERVAL_SEC * sampleRate).toInt()
            val vadWindow = 512  // silero v4 固定窗口
            val segBuf = ArrayList<Float>(windowSamples)  // 当前语音段（自语音开始累积）
            var vadRemain = FloatArray(0)                 // 不足 512 样本的余量
            var speechActive = false                      // VAD isSpeechDetected 状态
            var sincePartial = 0                          // 距上次 partial 的样本数

            fun resetSegment() {
                segBuf.clear()
                sincePartial = 0
            }

            val chunk = ShortArray((0.1 * sampleRate).toInt())
            try {
                while (running) {
                    val n = record!!.read(chunk, 0, chunk.size)
                    if (n <= 0) continue
                    // 增益 + 钳制
                    val raw = FloatArray(n) { (chunk[it] / 32768.0f) * micGain }
                    for (i in raw.indices) {
                        if (raw[i] > 1f) raw[i] = 1f else if (raw[i] < -1f) raw[i] = -1f
                    }

                    // 撤销请求：清空当前段 + 通知 UI
                    if (pendingReset) {
                        pendingReset = false
                        resetSegment()
                        vad.reset()
                        speechActive = false
                        onPartial("")
                        AppLog.i(TAG, "已撤销当前识别内容")
                        continue
                    }

                    // —— 喂 VAD（512 样本窗口） ——
                    vadRemain = vadRemain + raw
                    var off = 0
                    while (vadRemain.size - off >= vadWindow) {
                        vad.acceptWaveform(vadRemain.copyOfRange(off, off + vadWindow))
                        off += vadWindow
                    }
                    vadRemain = vadRemain.copyOfRange(off, vadRemain.size)

                    val detected = vad.isSpeechDetected()
                    if (detected) {
                        speechActive = true
                        // 说话中：累积音频
                        for (s in raw) segBuf.add(s)
                        // 控制内存：超过单句上限丢弃最老样本
                        if (segBuf.size > MAX_SEGMENT_SAMPLES) {
                            val drop = segBuf.size - MAX_SEGMENT_SAMPLES
                            segBuf.subList(0, drop).clear()
                        }
                        // 周期性 partial：取最近 windowSamples 识别
                        sincePartial += n
                        if (sincePartial >= partialIntervalSamples && segBuf.size >= vadWindow * 4) {
                            sincePartial = 0
                            val window = if (segBuf.size > windowSamples)
                                segBuf.subList(segBuf.size - windowSamples, segBuf.size).toFloatArray()
                            else segBuf.toFloatArray()
                            val text = asr.recognize(window)
                            if (text.isNotBlank()) onPartial(text)
                        }
                    } else if (speechActive) {
                        // VAD 报告语音结束：产出完整段
                        while (!vad.empty()) {
                            val seg = vad.front()
                            vad.pop()
                            val samples = seg.samples
                            if (samples.size >= vadWindow * 2) {  // 过滤极短噪声
                                val text = asr.recognize(samples)
                                if (text.isNotBlank()) {
                                    AppLog.i(TAG, "端点命中，final=\"$text\"")
                                    onFinal(text)
                                } else {
                                    onPartial("")  // 空识别：清掉 UI 残留 partial
                                }
                            }
                        }
                        resetSegment()
                        speechActive = false
                    }
                    // 未开始说话：静默，丢弃（不累积）
                }
            } catch (e: Throwable) {
                AppLog.e(TAG, "QNN 识别线程异常", e)
            } finally {
                AppLog.i(TAG, "QNN 识别线程结束")
            }
        }
    }

    override fun stop() {
        if (!running && workThread == null) return
        running = false
        try { record?.stop() } catch (_: Throwable) {}
        workThread?.join(1500)  // 离线识别一次可能略慢
        workThread = null
        record?.release()
        record = null
        AecManager.disable(aec)
        aec = null
    }

    override fun resetStream() {
        pendingReset = true
        AppLog.i(TAG, "请求撤销当前识别内容")
    }

    override fun release() {
        stop()
        vad.release()
        asr.release()
    }

    companion object {
        private const val TAG = "QASR"
        /** partial 输出间隔（秒）：太密会频繁触发 NPU 推理占带宽，太疏出字慢 */
        private const val PARTIAL_INTERVAL_SEC = 1.0f
        /** 单句最大保留样本（15s），超出丢弃最老部分 */
        private const val MAX_SEGMENT_SAMPLES = (15 * 16000).toInt()
    }
}
