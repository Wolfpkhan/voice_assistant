package com.sherva.voiceassistant.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.QnnConfig
import com.sherva.voiceassistant.AppLog
import com.sherva.voiceassistant.ModelPaths
import java.io.File

/**
 * ASR 离线引擎：封装 paraformer（中文），支持 CPU / QNN(HTP NPU) 双 provider。
 *
 * - CPU：paraformer-zh-2023-09-14 (int8)，骁龙 8 Gen2 4 线程单句约 100~200ms。
 * - QNN：paraformer-zh-2025-10-07 QNN 5 秒窗（libencoder/libpredictor/libdecoder.so），
 *   HTP NPU 推理可降到几十 ms。要求：
 *     1) assets 内有 QNN 模型包（scripts/download-models.sh --qnn）
 *     2) jniLibs/arm64-v8a 有 libQnnHtp.so + libQnnSystem.so（scripts/download-qnn-libs.sh）
 *     3) 设备为骁龙且 HTP arch 匹配（888+: V68+，8 Gen2: V73）
 *
 * QNN 初始化失败（模型/so 缺失、芯片不支持等）时自动回退 CPU 版本，
 * 通过 [provider] 暴露实际生效的 provider。
 *
 * 如需切换其它模型：
 *   - SenseVoice 多语种：把 modelConfig 改为 senseVoice
 */
class AsrEngine(
    context: Context,
    /** 期望使用 QNN(HTP)；不可用时自动回退 CPU */
    private val useQnn: Boolean = false,
    private val numThreads: Int = 4,   // 骁龙多核，4 线程性价比较高（QNN 模式忽略）
) {
    private val appContext = context.applicationContext

    /** 实际生效的 provider：请求 qnn 且初始化成功为 "qnn"，否则 "cpu" */
    val provider: String

    private val recognizer: OfflineRecognizer

    init {
        var qnnRecognizer: OfflineRecognizer? = null
        if (useQnn && ModelPaths.qnnModelInAssets(appContext.assets)) {
            try {
                qnnRecognizer = buildQnnRecognizer()
                AppLog.i("ASR", "QNN(HTP) OfflineRecognizer 构造成功")
            } catch (e: Throwable) {
                AppLog.e("ASR", "QNN 初始化失败：${e.message}", e)
            }
        } else if (useQnn) {
            AppLog.w("ASR", "QNN 模型不在 assets 中（未运行 download-models.sh --qnn?）")
        }
        if (qnnRecognizer == null) {
            if (useQnn) {
                // ★ 回退决策上移：QNN 模式失败直接抛，由 VoiceAssistant 换用流式 CPU 引擎
                //   （assets 内无离线 paraformer，不能在此构造 CPU 离线引擎）
                throw IllegalStateException("QNN 初始化失败，请查看日志 tag ASR")
            }
            recognizer = buildCpuRecognizer()
        } else {
            recognizer = qnnRecognizer
        }
        provider = if (qnnRecognizer != null) "qnn" else "cpu"
    }

    /** CPU：普通 int8 ONNX，assets 直读 */
    private fun buildCpuRecognizer(): OfflineRecognizer {
        AppLog.i("ASR", "构造 CPU OfflineRecognizer: model=${ModelPaths.ASR_MODEL}, threads=$numThreads")
        return OfflineRecognizer(
            assetManager = appContext.assets,
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
        ).also { AppLog.i("ASR", "CPU OfflineRecognizer 构造成功") }
    }

    /**
     * QNN：文件路径模式构造（OfflineRecognizer(assetManager=null) → newFromFile）。
     *
     * 1. 后端库优先用系统自带 QNN（如 /odm/lib64/npuhw/qnnv3/，厂商签名，
     *    host 库与 Skel 版本配套）：unsigned Skel 在商用机上常被 cDSP 拒载
     *    （deviceCreate 报 INVALID_CONFIG/14001）。系统无 QNN 时用 APK 自带的。
     * 2. prependAdspLibraryPath：让 DSP 能找到 Skel（否则 error 1008/14001）
     * 3. 模型 .so 从 assets 拷到 filesDir（QNN 以 dlopen 方式加载，需真实文件）
     * 4. contextBinary(*.bin) 不存在时跳过拷贝 —— 首次运行由 HTP 编译生成
     */
    private fun buildQnnRecognizer(): OfflineRecognizer {
        // —— 后端库选择：优先 APK 自带（与编译头文件版本一致 2.40）；
        //    系统签名版（odm）仅作后备——版本不匹配时 dlopen 可能因符号缺失失败
        val sysDir = File("/odm/lib64/npuhw/qnnv3")
        val sysHtp = File(sysDir, ModelPaths.QNN_BACKEND_LIB)
        val backendLib: String
        val systemLib: String
        // 先试 APK 自带 2.40（与重编的 jni 库 ABI/接口版本配套）
        backendLib = ModelPaths.QNN_BACKEND_LIB
        systemLib = ModelPaths.QNN_SYSTEM_LIB
        AppLog.i("ASR", "使用 APK 自带 QNN (2.40)；系统后备: ${if (sysHtp.exists()) sysDir else "无"}")
        // ADSP 路径：nativeLibraryDir（Skel 所在）前插；系统目录也加上（若存在，多一路 Skel 来源）
        OfflineRecognizer.prependAdspLibraryPath(appContext.applicationInfo.nativeLibraryDir)
        if (sysHtp.exists()) OfflineRecognizer.prependAdspLibraryPath(sysDir.absolutePath)

        val dir = File(appContext.filesDir, ModelPaths.ASR_QNN_DIR)
        dir.mkdirs()

        fun copyAsset(assetPath: String): String {
            val out = File(appContext.filesDir, assetPath)
            if (!out.exists()) {
                out.parentFile?.mkdirs()
                appContext.assets.open(assetPath).use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                AppLog.i("ASR", "拷贝 QNN 模型: $assetPath -> filesDir")
            }
            return out.absolutePath
        }

        val models = ModelPaths.ASR_QNN_MODELS.split(',').map { copyAsset(it.trim()) }.joinToString(",")
        // context binary 若已在 assets（-binary- 预编译包）或已生成于 filesDir，则使用之
        val bins = ModelPaths.ASR_QNN_CONTEXT_BINARIES.split(',').joinToString(",") { p ->
            val assetsBin = ModelPaths.assetExists(appContext.assets, p.trim())
            val fileBin = File(appContext.filesDir, p.trim())
            when {
                fileBin.exists() -> fileBin.absolutePath
                assetsBin -> copyAsset(p.trim())
                else -> File(appContext.filesDir, p.trim()).absolutePath  // 首次运行将生成
            }
        }
        val tokens = copyAsset(ModelPaths.ASR_QNN_TOKENS)

        AppLog.i("ASR", "构造 QNN OfflineRecognizer: dir=${dir.name}, backend=$backendLib")
        return OfflineRecognizer(
            assetManager = null,   // 文件路径模式（JNI newFromFile）
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    paraformer = OfflineParaformerModelConfig(
                        model = models,
                        qnnConfig = QnnConfig(
                            backendLib = backendLib,
                            contextBinary = bins,
                            systemLib = systemLib,
                        ),
                    ),
                    tokens = tokens,
                    numThreads = 1,   // QNN 推理在 HTP 上，CPU 侧 1 线程足够
                    provider = "qnn",
                    modelType = "paraformer",
                ),
                decodingMethod = "greedy_search",
            )
        )
    }

    /** 从 libQnnSystem.so 提取 QNN SDK 版本号（日志诊断用） */
    private fun qnnLibVersion(htp: File): String {
        val sys = File(htp.parentFile, ModelPaths.QNN_SYSTEM_LIB)
        if (!sys.canRead()) return "版本未知"
        return runCatching {
            sys.inputStream().use { ins ->
                val buf = ByteArray(4 * 1024 * 1024)
                var off = 0
                val head = ins.read(buf)
                val s = String(buf, 0, head, Charsets.ISO_8859_1)
                Regex("2\\.\\d{2}\\.\\d").find(s)?.value ?: "版本未知"
            }
        }.getOrDefault("版本未知")
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
