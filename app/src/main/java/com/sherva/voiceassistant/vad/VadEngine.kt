package com.sherva.voiceassistant.vad

import android.content.Context
import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.sherva.voiceassistant.AppLog
import com.sherva.voiceassistant.ModelPaths
import com.sherva.voiceassistant.audio.AudioRecorder
import kotlinx.coroutines.CoroutineScope

/**
 * VAD 引擎：封装 silero-vad。
 *
 * 工作流程：
 *   start() → 内部 AudioRecorder 持续采集 16kHz 帧
 *          → 每帧喂给 sherpa Vad
 *          → 检出完整语音段时通过 [onSpeechSegment] 回调（含起止样本与 PCM）
 *
 * 端点判定参数（可在 [config] 调整）：
 *   minSilenceDuration = 0.5s  → 说完停顿 0.5s 判定该句结束
 *   maxSpeechDuration  = 20s   → 单句最长 20s 强制截断，避免无限录音
 */
class VadEngine(
    private val context: Context,
    val config: VadEngineConfig = VadEngineConfig(),
) {
    /** 端点检测调参。 */
    data class VadEngineConfig(
        val threshold: Float = 0.7f,         // 提高阈值，减少误触发（0.5→0.7）
        val minSilenceDuration: Float = 0.8f,// 静默多久判定说完（0.5→0.8s 更稳）
        val minSpeechDuration: Float = 0.5f, // 太短的脉冲不当作语音（0.25→0.5s）
        val maxSpeechDuration: Float = 15f,  // 单句上限（20→15s）
        val windowSize: Int = 512,     // 16kHz silero v4
        val numThreads: Int = 2,
    )

    private val vad: Vad = run {
        AppLog.i("VAD", "构造 Vad: model=${ModelPaths.VAD_MODEL}, window=${config.windowSize}")
        Vad(
            assetManager = context.assets,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = ModelPaths.VAD_MODEL,
                    threshold = config.threshold,
                    minSilenceDuration = config.minSilenceDuration,
                    minSpeechDuration = config.minSpeechDuration,
                    windowSize = config.windowSize,
                    maxSpeechDuration = config.maxSpeechDuration,
                ),
                sampleRate = 16000,
                numThreads = config.numThreads,
                provider = "cpu",
                debug = false,
            )
        ).also { AppLog.i("VAD", "Vad 构造成功") }
    }

    private val recorder = AudioRecorder(chunkSamples = config.windowSize)

    /** 启动 VAD（含录音）。检测到完整语音段时回调 [onSpeechSegment]。 */
    fun start(scope: CoroutineScope, onSpeechSegment: (Segment) -> Unit) {
        recorder.start(scope) { chunk ->
            // 喂一帧
            vad.acceptWaveform(chunk)
            // 排空所有已就绪的语音段
            while (!vad.empty()) {
                val seg: SpeechSegment = vad.front()
                vad.pop()
                if (seg.samples.isNotEmpty()) {
                    onSpeechSegment(
                        Segment(
                            startSample = seg.start,
                            samples = seg.samples,
                            sampleRate = 16000,
                        )
                    )
                }
            }
        }
    }

    /** 主动冲刷内部缓冲（用于停止时取出残留段）。 */
    fun flush(onSegment: (Segment) -> Unit) {
        vad.flush()
        while (!vad.empty()) {
            val seg = vad.front()
            vad.pop()
            if (seg.samples.isNotEmpty()) {
                onSegment(Segment(seg.start, seg.samples, 16000))
            }
        }
    }

    fun stop() {
        recorder.stop()
    }

    /** 重置 VAD 状态，丢弃未结束的语音缓冲。 */
    fun reset() {
        vad.reset()
    }

    fun release() {
        stop()
        vad.release()
    }

    /** 一段完整语音（端点检测后产出）。 */
    data class Segment(
        val startSample: Int,
        val samples: FloatArray,   // 16kHz mono float PCM
        val sampleRate: Int,
    )
}
