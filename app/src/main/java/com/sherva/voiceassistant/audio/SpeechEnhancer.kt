package com.sherva.voiceassistant.audio

import android.content.Context
import com.k2fsa.sherpa.onnx.DenoisedAudio
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.sherva.voiceassistant.AppLog
import com.sherva.voiceassistant.ModelPaths

/**
 * 实时语音增强引擎：封装 GTCRN（流式）。
 *
 * 作用：麦克风原始 PCM（含 TTS 扬声器回声 + 环境噪声）→ 剥离回声/噪声 → 干净人声。
 *
 * ★ 这是全双工打断对话的关键：TTS 播报时，麦克风会同时录入扬声器声音（回声）。
 *   直接把原始信号喂给 VAD，会被回声误判为“用户开口”。GTCRN 实时消回声后，
 *   VAD/ASR 只对真正的近端人声反应，外放也能稳定工作。
 *
 * 用法（流式逐帧）：
 *   val clean = denoiser.process(rawChunk, 16000)  // 返回等长干净 PCM
 * GTCRN 以 frameShiftInSamples（默认 512）为处理粒度，调用方按该粒度喂入即可。
 */
class SpeechEnhancer(
    context: Context,
) {
    private val denoiser = run {
        AppLog.i("Enhancer", "构造 OnlineSpeechDenoiser: ${ModelPaths.DENOISER_MODEL}")
        OnlineSpeechDenoiser(
            assetManager = context.assets,
            config = OnlineSpeechDenoiserConfig(
                model = OfflineSpeechDenoiserModelConfig(
                    gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(
                        model = ModelPaths.DENOISER_MODEL,
                    ),
                    numThreads = 1,
                    provider = "cpu",
                ),
            ),
        ).also {
            AppLog.i("Enhancer", "Denoiser 构造成功, sampleRate=${it.sampleRate}, frameShift=${it.frameShiftInSamples}")
        }
    }

    val sampleRate: Int get() = denoiser.sampleRate
    val frameShiftInSamples: Int get() = denoiser.frameShiftInSamples

    /** 处理一帧原始音频，返回降噪后的干净音频（长度可能不等于输入，按 frameShift 对齐）。 */
    fun process(samples: FloatArray, sampleRate: Int): FloatArray {
        val audio: DenoisedAudio = denoiser.run(samples, sampleRate)
        return audio.samples
    }

    /** 冲刷内部残留缓冲。 */
    fun flush(): FloatArray = denoiser.flush().samples

    /** 重置内部状态（每次新一轮对话/打断恢复时调用）。 */
    fun reset() = denoiser.reset()

    fun release() = denoiser.release()
}
