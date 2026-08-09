package com.sherva.voiceassistant

import android.content.Context
import java.io.File

/**
 * 集中管理所有 sherpa-onnx 模型的路径。
 *
 * 模型位于 assets/models/ 下（由 scripts/download-models.sh 下载）。
 * sherpa-onnx 的各引擎构造函数均接受 AssetManager，路径相对 assets 根。
 *
 * 如需切换模型（如 VITS-Melo / QNN 版 ASR），只需改这里的常量。
 */
object ModelPaths {
    private const val ROOT = "models"

    // ---------- 语音增强：GTCRN（实时降噪/消回声，用于全双工打断） ----------
    const val DENOISER_MODEL = "$ROOT/gtcrn_simple.onnx"

    // ---------- VAD：silero-vad（旧离线方案用，流式方案不再需要，保留以备） ----------
    const val VAD_MODEL = "$ROOT/silero_vad.onnx"

    // ---------- ASR（离线）：paraformer-zh-2023-09-14 (int8) ----------
    private const val ASR_DIR = "$ROOT/sherpa-onnx-paraformer-zh-2023-09-14"
    const val ASR_MODEL = "$ASR_DIR/model.int8.onnx"
    const val ASR_TOKENS = "$ASR_DIR/tokens.txt"

    // ---------- ASR（流式）：streaming-zipformer-bilingual-zh-en-2023-02-20 (int8) ----------
    // 边说边出字、结果连续纠正、内置端点检测（无需单独 VAD）
    private const val STREAM_DIR = "$ROOT/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20"
    const val STREAM_ENCODER = "$STREAM_DIR/encoder-epoch-99-avg-1.int8.onnx"
    const val STREAM_DECODER = "$STREAM_DIR/decoder-epoch-99-avg-1.onnx"
    const val STREAM_JOINER = "$STREAM_DIR/joiner-epoch-99-avg-1.int8.onnx"
    const val STREAM_TOKENS = "$STREAM_DIR/tokens.txt"

    // ---------- TTS：matcha-icefall-zh-baker + vocos 声码器 ----------
    private const val TTS_DIR = "$ROOT/matcha-icefall-zh-baker"
    const val TTS_ACOUSTIC = "$TTS_DIR/model-steps-3.onnx"
    const val TTS_LEXICON = "$TTS_DIR/lexicon.txt"
    const val TTS_TOKENS = "$TTS_DIR/tokens.txt"
    // vocoder 放在 models 根目录（sherpa 约定）
    const val TTS_VOCODER = "$ROOT/vocos-22khz-univ.onnx"

    /**
     * 运行时下载场景：把 assets 模型释放到 filesDir，返回绝对路径。
     * 当前骨架采用 assets 直读（各引擎接受 AssetManager），此方法留作瘦身 APK 时使用。
     */
    fun ensureExtracted(context: Context, assetPath: String): File {
        val out = File(context.filesDir, assetPath)
        if (out.exists()) return out
        out.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }
}
