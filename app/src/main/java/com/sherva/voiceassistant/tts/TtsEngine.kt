package com.sherva.voiceassistant.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.sherva.voiceassistant.AppLog
import com.sherva.voiceassistant.ModelPaths
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

/**
 * TTS 引擎：封装 Kokoro int8 multi-lang（中英双语，103 音色）。
 *
 * 重点：Kokoro 的 espeak-ng-data 必须释放到 filesDir（eSpeak 是 POSIX 文件访问，
 * AssetManager 不支持）。model/voices/tokens/lexicon 仍走 AssetManager。
 *
 * 参考实现：https://github.com/vishalkdn/VocalBridge
 */

/**
 * TTS 引擎：封装 matcha-icefall-zh-baker + vocos 声码器。
 *
 * 对齐 sherpa-onnx 官方 SherpaOnnxTts demo：
 *   - AudioTrack 在引擎构造后即创建并进入 play 状态（USAGE_MEDIA）
 *   - 用 generateWithConfigAndCallback + GenerationConfig(sid, speed) 让 sherpa 处理语速
 *   - 每次播报前 pause/flush/play，复用同一个 track
 *   - 流式 callback：边合成边写入 AudioTrack，首响低
 */
class TtsEngine(
    context: Context,
    private val numThreads: Int = 2,
) {
    companion object { private const val TAG = "TtsEngine" }

    private val tts = run {
        AppLog.i("TTS", "构造 OfflineTts (Kokoro): model=${ModelPaths.TTS_MODEL}, voices=${ModelPaths.TTS_VOICES}")
        // ★ Kokoro 的 espeak-ng-data 需要 POSIX 文件访问，必须先释放到 filesDir
        val espeakFilesDir = extractEspeakData(context)
        OfflineTts(
            assetManager = context.assets,
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = ModelPaths.TTS_MODEL,
                        voices = ModelPaths.TTS_VOICES,
                        tokens = ModelPaths.TTS_TOKENS,
                        lexicon = ModelPaths.TTS_LEXICON,  // 多 lexicon：中文+美音英文（逗号分隔）
                        dataDir = espeakFilesDir.absolutePath,  // ★ filesDir 绝对路径，eSpeak 用 POSIX 访问
                        lengthScale = 1.0f,
                    ),
                    numThreads = numThreads,
                    provider = "cpu",
                ),
                maxNumSentences = 2,
                silenceScale = 0.2f,
            )
        ).also {
            AppLog.i("TTS", "OfflineTts (Kokoro) 构造成功, sampleRate=${it.sampleRate()}, numSpeakers=${it.numSpeakers()}")
        }
    }

    /**
     * 将 assets/models/.../espeak-ng-data 释放到 filesDir，返回文件对象。
     * 首次拷贝后写个标记文件，后续跳过（避免启动时重复拷贝 19MB）。
     */
    private fun extractEspeakData(context: Context): File {
        val appCtx = context.applicationContext
        val dest = File(appCtx.filesDir, "kokoro-espeak-ng-data")
        val marker = File(dest, ".extracted")
        if (marker.exists()) {
            AppLog.i("TTS", "espeak-ng-data 已就绪: ${dest.absolutePath}")
            return dest
        }
        val assetPath = ModelPaths.TTS_DATA_DIR  // "models/kokoro-int8-multi-lang-v1_1/espeak-ng-data"
        AppLog.i("TTS", "释放 espeak-ng-data: $assetPath → $dest")
        dest.mkdirs()
        copyAssetDir(appCtx, assetPath, dest)
        marker.writeText("ok")
        AppLog.i("TTS", "espeak-ng-data 释放完成")
        return dest
    }

    private fun copyAssetDir(context: Context, assetPath: String, destDir: File) {
        val am = context.assets
        val entries = am.list(assetPath) ?: return
        for (entry in entries) {
            val src = "$assetPath/$entry"
            val out = File(destDir, entry)
            if (out.exists()) continue  // 递归过程中可能遇到部分已存在的
            if (am.list(src)?.isNotEmpty() == true) {
                // 子目录
                out.mkdirs()
                copyAssetDir(context, src, out)
            } else {
                // 文件
                am.open(src).use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private val sampleRate = tts.sampleRate()
    private var track: AudioTrack? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var stopped = false
    private var speakJob: Job? = null

    /** 创建并启动 AudioTrack（对齐官方：USAGE_MEDIA，play 状态）。 */
    private fun ensureTrack(): AudioTrack {
        track?.let { return it }
        val bufLength = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val attr = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)   // 官方用 MEDIA；ASSISTANT 在部分机型被静音路由
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()
        val t = AudioTrack(
            attr, format, bufLength, AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        t.play()
        track = t
        AppLog.i("TTS", "AudioTrack 创建并 play, sampleRate=$sampleRate")
        return t
    }

    fun isSpeaking() = speakJob?.isActive == true

    /**
     * 异步合成并播放 [text]（流式 callback）。
     * @param speed 语速倍率（1.0 正常）；直接传给 sherpa 的 GenerationConfig.speed
     * @param onComplete 播放结束（自然/被打断）回调（主线程）
     */
    fun speak(
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f,
        onComplete: (() -> Unit)? = null,
    ) {
        if (text.isBlank()) { onComplete?.invoke(); return }
        // 取消上一句（如有）后等待其退出，避免两个生成线程并发
        speakJob?.cancel()
        speakJob = scope.launch {
            // 若上一个还在跑，cancel 后短暂让出
            // （cancel 是协作式，generateWithCallback 内部靠 stopped 检查）
            val t = ensureTrack()
            // 对齐官方：每次播报前重置 track 状态
            t.pause(); t.flush(); t.play()
            stopped = false
            AppLog.i("TTS", "开始合成播放: \"${text.take(30)}\" speed=$speed")
            try {
                val genConfig = GenerationConfig(sid = sid, speed = speed)
                val audio = tts.generateWithConfigAndCallback(
                    text = text,
                    config = genConfig,
                    callback = ::onSamples,
                )
                AppLog.i("TTS", "合成完成, 样本数=${audio.samples.size}")
            } catch (e: Throwable) {
                AppLog.e("TTS", "TTS 合成失败", e)
            } finally {
                onComplete?.invoke()
            }
        }
    }

    /** sherpa 流式回调：返回 1 继续，0 中止。由 C++ 调用。 */
    private fun onSamples(samples: FloatArray): Int {
        if (stopped) {
            track?.stop()
            return 0
        }
        track?.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        return 1
    }

    /** 立即停止当前播报。 */
    fun stop() {
        stopped = true
        speakJob?.cancel()
        speakJob = null
        track?.let { runCatching { it.pause(); it.flush() } }
    }

    fun release() {
        stop()
        scope.cancel()
        track?.runCatching { stop(); release() }
        track = null
        tts.release()
    }
}
