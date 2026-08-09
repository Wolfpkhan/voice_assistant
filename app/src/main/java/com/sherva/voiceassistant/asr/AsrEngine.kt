package com.sherva.voiceassistant.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.sherva.voiceassistant.AppLog
import com.sherva.voiceassistant.ModelPaths

/**
 * ASR 引擎：封装 paraformer-zh-2023-09-14 (int8)。
 *
 * 非流式：把一整段语音(VAD 产出的 Segment)一次性送入识别。
 * 在骁龙 8 Gen2 CPU 上单句约 100~200ms；若改用 QNN 版 paraformer 可降到几十 ms。
 *
 * 如需切换模型：
 *   - SenseVoice 多语种：把 modelConfig 改为 senseVoice
 *   - QNN 加速：换 model.int8.onnx 为 QNN 版 lib，并设 provider="qnn" + qnnConfig
 */
class AsrEngine(
    context: Context,
    private val numThreads: Int = 4,   // 骁龙多核，4 线程性价比较高
) {
    private val recognizer = run {
        AppLog.i("ASR", "构造 OfflineRecognizer: model=${ModelPaths.ASR_MODEL}, threads=$numThreads")
        OfflineRecognizer(
            assetManager = context.assets,
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    paraformer = OfflineParaformerModelConfig(
                        model = ModelPaths.ASR_MODEL,
                    ),
                    tokens = ModelPaths.ASR_TOKENS,
                    numThreads = numThreads,
                    provider = "cpu",
                    modelType = "paraformer",
                ),
                decodingMethod = "greedy_search",
            )
        ).also { AppLog.i("ASR", "OfflineRecognizer 构造成功") }
    }

    /**
     * 识别一段完整语音。
     * @param samples 16kHz mono float PCM（来自 VAD 段）
     * @return 识别文本（已做空格规整）
     */
    fun recognize(samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    fun release() = recognizer.release()
}
