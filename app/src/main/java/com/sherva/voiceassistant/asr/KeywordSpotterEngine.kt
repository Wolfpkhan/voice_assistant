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
    /** ★ 全局回声消除：true 时用 VOICE_COMMUNICATION + MODE_IN_COMMUNICATION。
     *     见 [StreamingAsrEngine.globalAec]。 */
    private val globalAec: Boolean = false,
    /** ★ 硬件噪声抑制（NoiseSuppressor）：稳态噪声（风扇/空调/车噪）显著抑制，默认开。
     *     设备不支持时自动降级（isAvailable=false 则不启用）。 */
    private val useNoiseSuppressor: Boolean = true,
    /** ★ GTCRN 人声增强：模型级降噪（压噪声保人声），嘈杂环境提升命中率。
     *     默认关：安静环境可能过度处理反而损特征；嘈杂时手动开。 */
    private val useGtcrn: Boolean = false,
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
    private var ns: android.media.audiofx.NoiseSuppressor? = null  // ★ 硬件噪声抑制
    /** ★ GTCRN 人声增强器（懒加载，按需构造避免闲置内存） */
    private var enhancer: SpeechEnhancer? = null
    private fun getEnhancer(): SpeechEnhancer =
        enhancer ?: com.sherva.voiceassistant.audio.SpeechEnhancer(appContext).also { enhancer = it }

    /** ★ 蓝牙 SCO：true 时 start() 在工作线程建 SCO 通道后路由到蓝牙麦。
     *  经典 HFP 耳机必须 SCO 才能出声（仅 setPreferredDevice 是假路由）。 */
    private var scoActive: Boolean = false
    private var scoEnabled: Boolean = false

    /** 当前活跃 stream（每次 start() 重建）。 */
    private var stream: OnlineStream? = null
    private var record: AudioRecord? = null
    @Volatile private var running = false
    /** KWS 是否在跑（供 VoiceAssistant 检测切后台后是否需重启）。 */
    val isRunning: Boolean get() = running
    private var workThread: Thread? = null

    /** ★ 蓝牙 SCO 开关（外部设置接线用，start 前调用）。 */
    fun setBluetoothSco(enabled: Boolean) { scoEnabled = enabled }

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
        val source = if (globalAec) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                     else MediaRecorder.AudioSource.VOICE_RECOGNITION
        record = AudioRecord(
            source,
            sampleRate, channelConfig, audioFormat, bufBytes
        )
        check(record?.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失败" }
        // ★ MODE_IN_COMMUNICATION 由 VoiceAssistant 统一管理，此处不切
        // ★ 硬件 AEC：消除 TTS / 音乐播放时的扬声器回声，避免误唤醒
        aec = AecManager.enable(record!!)
        // ★ 硬件噪声抑制：稳态噪声（风扇/空调/车噪）——设备不支持则静默跳过
        if (useNoiseSuppressor) {
            try {
                if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                    ns = android.media.audiofx.NoiseSuppressor.create(record!!.audioSessionId)
                    ns?.enabled = true
                    AppLog.i("KWS", "✓ 硬件噪声抑制已启用 (sessionId=${record!!.audioSessionId})")
                } else {
                    AppLog.i("KWS", "硬件噪声抑制不可用（设备不支持），跳过")
                }
            } catch (e: Throwable) {
                AppLog.i("KWS", "噪声抑制启用失败: ${e.message}")
            }
        }
        if (useGtcrn) {
            // 预构造 GTCRN（模型加载在 start 时做，避免命中时才加载的延迟）
            try { getEnhancer() } catch (e: Throwable) { AppLog.i("KWS", "GTCRN 加载失败: ${e.message}") }
            AppLog.i("KWS", "✓ GTCRN 人声增强已启用")
        }
        AppLog.i("KWS", "AudioRecord 实际采样率=${record!!.sampleRate} Hz, 请求=$sampleRate Hz")
        // ★ 关键：用 actual sample rate 喂入 acceptWaveform（Android 可能不同）
        val actualSampleRate = record!!.sampleRate
        // createStream 接受 keyword 作为标识符；传空串使用默认（首条关键词）
        stream = spotter.createStream("")
        record!!.startRecording()
        running = true
        AppLog.i("KWS", "唤醒词检测已启动")

        workThread = thread(true, name = "kws-spotter") {
            // ★ 蓝牙 SCO：在工作线程建通道（阻塞最多 3s，不卡调用方），
            //   成功后路由到 SCO 麦；失败静默用机内麦。
            if (scoEnabled) {
                scoActive = com.sherva.voiceassistant.audio.ScoAudioRouter.connect(appContext)
                if (scoActive) routeToBluetoothMic(record!!)
            }
            val intervalSamples = (0.1 * sampleRate).toInt()   // 100ms
            val buf = ShortArray(intervalSamples)
            var totalFrames = 0
            try {
                while (running) {
                    val n = record!!.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    // ★ 输入链：原始 PCM →（可选）GTCRN 人声增强 → KWS
                    //   安静：原始（保真）；嘈杂：GTCRN（压噪保人声）
                    val raw: FloatArray = if (useGtcrn) {
                        try {
                            val pcm = FloatArray(n) { buf[it] / 32768.0f }
                            getEnhancer().run { process(pcm, actualSampleRate) }
                        } catch (e: Throwable) {
                            FloatArray(n) { buf[it] / 32768.0f }  // 降级原始
                        }
                    } else {
                        FloatArray(n) { buf[it] / 32768.0f }
                    }
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
    /** ★ 路由到蓝牙麦克风：枚举系统输入设备，优先 BLUETOOTH_SCO / BLUETOOTH_HEADSET /
     *  BLE_HEADSET（LE Audio）。成功返回 true（日志记录选中的设备名）。
     *  无蓝牙麦克风时静默返回 false（继续用机内麦）。 */
    private fun routeToBluetoothMic(rec: AudioRecord): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return false
        return try {
            val am = appContext.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            val inputs = am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
            val bt = inputs.firstOrNull {
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            if (bt != null) {
                val ok = rec.setPreferredDevice(bt)
                AppLog.i("KWS", if (ok) "✓ 录音路由到蓝牙麦克风: ${bt.productName}" else "蓝牙麦路由失败（setPreferredDevice=false），继续用机内麦")
                ok
            } else {
                AppLog.i("KWS", "无蓝牙输入设备，用机内麦")
                false
            }
        } catch (e: Throwable) {
            AppLog.i("KWS", "蓝牙路由异常: ${e.message}")
            false
        }
    }

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
        // ★ 断开 SCO 已上提到会话级（VoiceAssistant.stop/release）：
        //   唤醒激活后 ASR 接管聆听仍需蓝牙麦，此处不 disconnect
        // ★ 释放噪声抑制效果器
        runCatching { ns?.enabled = false; ns?.release() }
        ns = null
        // ★ MODE_IN_COMMUNICATION 由 VoiceAssistant 统一管理，此处不切回
    }

    fun release() {
        stop()
        spotter.release()
    }
}
