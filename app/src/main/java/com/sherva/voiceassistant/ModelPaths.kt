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

    // ---------- TTS：kokoro-int8-multi-lang-v1_1 (中英双语，103 音色) ----------
    // 替换原来的 matcha-icefall-zh-baker（仅中文女声）。Kokoro 单模型原生支持中英混合朗读。
    private const val TTS_DIR = "$ROOT/kokoro-int8-multi-lang-v1_1"
    const val TTS_MODEL = "$TTS_DIR/model.int8.onnx"
    const val TTS_VOICES = "$TTS_DIR/voices.bin"
    const val TTS_TOKENS = "$TTS_DIR/tokens.txt"
    // 多 lexicon 用逗号分隔（中文+英文）；dataDir 指向 espeak-ng-data（英文发音必得）
    const val TTS_LEXICON = "$TTS_DIR/lexicon-zh.txt,$TTS_DIR/lexicon-us-en.txt"
    const val TTS_DATA_DIR = "$TTS_DIR/espeak-ng-data"

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
