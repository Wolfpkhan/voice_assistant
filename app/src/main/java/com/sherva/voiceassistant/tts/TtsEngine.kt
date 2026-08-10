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
    companion object {
        private const val TAG = "TtsEngine"
        private const val DIGITS = "0123456789"
        private const val CN_DIGITS = "零一二三四五六七八九"
        /**
         * 阿拉伯数字 → 中文数字（逐位替换）。
         * Kokoro espeak 默认逐位读英文 ("773" → "seven seven three")，
         * 中文场景下应读 "七七三" / "七七三"。
         */
        fun digitsToChinese(text: String): String {
            val sb = StringBuilder(text.length)
            for (c in text) {
                val idx = DIGITS.indexOf(c)
                if (idx >= 0) sb.append(CN_DIGITS[idx]) else sb.append(c)
            }
            return sb.toString()
        }
    }

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
                // ★ silenceScale = 0.05：极小静音帧（只保留声母/韵母间必要静音）
                //   默认 0.2 在中英切换处有可感知停顿
                silenceScale = 0.05f,
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
    /** ★ 代次计数：每次新 speak 自增。旧任务的 callback 检查到代次不一致就退出，防止新旧任务重复写 AudioTrack。 */
    @Volatile private var generation = 0
    private var speakJob: Job? = null

    /** 创建并启动 AudioTrack（一次性创建后持续 play，不每句重置）。 */
    private fun ensureTrack(): AudioTrack {
        track?.let { existing ->
            if (existing.playState == AudioTrack.PLAYSTATE_STOPPED) {
                existing.play()
                AppLog.i("TTS", "AudioTrack 重新 play（状态=STOPPED）")
            }
            return existing
        }
        val bufLength = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val attr = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
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
        AppLog.i("TTS", "AudioTrack 创建并 play（持续状态）, sampleRate=$sampleRate")
        return t
    }

    fun isSpeaking() = speakJob?.isActive == true

    /**
     * 同步生成单句音频（不播放），返回 FloatArray。
     * 用于预生成下一句：当前句播放中，后台跑 Kokoro。
     */
    fun generateSync(text: String, sid: Int, speed: Float): FloatArray? {
        if (text.isBlank()) return null
        val normalized = digitsToChinese(text)
        val genConfig = GenerationConfig(sid = sid, speed = speed, silenceScale = 0.05f)
        return runCatching {
            tts.generateWithConfig(normalized, genConfig).samples
        }.onFailure { AppLog.e("TTS", "generateSync 失败", it) }.getOrNull()
    }

    /**
     * 流式播放单句音频（首响低），同时可选地预生成下一句。
     * 推荐使用 [speakWithPreGen]。
     */
    private suspend fun playOneStreaming(text: String, sid: Int, speed: Float, gen: Int) {
        val t = ensureTrack()
        val normalized = digitsToChinese(text)
        AppLog.i("TTS", "流式播放: \"${normalized.take(30)}\" gen=$gen")
        tts.generateWithConfigAndCallback(
            text = normalized,
            config = GenerationConfig(sid = sid, speed = speed, silenceScale = 0.05f),
            callback = { samples ->
                // ★ 代次检查：旧任务的回调不再写 AudioTrack
                if (stopped || generation != gen) return@generateWithConfigAndCallback 0
                t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                return@generateWithConfigAndCallback 1
            },
        )
    }

    /**
     * 同步播放预生成的 FloatArray（分块 write，避免冲爆 AudioTrack 缓冲）。
     */
    private suspend fun playOneSync(samples: FloatArray, gen: Int) {
        val t = ensureTrack()
        val chunkSize = 4096
        var pos = 0
        while (pos < samples.size && !stopped && generation == gen) {
            val n = minOf(chunkSize, samples.size - pos)
            t.write(samples, pos, n, AudioTrack.WRITE_BLOCKING)
            pos += n
        }
    }

    /**
     * 串行播报多个句子（先后台预生成下一句，句间停顿从 ~Kokoro 推理耗时压到 ~25ms）。
     *
     * 优化原理：
     *   - 句 N 还在播放时，后台启动句 N+1 的 Kokoro 同步推理
     *   - 句 N 播放完，句 N+1 音频可能已就绪 → 立即接续
     *   - AudioTrack 持续 play，句末静音由模型生成，不人为重置
     *
     * ★ 每句仅播一次：
     *   - i=0：流式播放句 0（首响低） + 预生成句 1
     *   - i=1..n-1：await 句 i 预生成 + 同步播放句 i + 启动句 i+1 预生成
     *
     * ★ 重复防止：启动前 cancelAndJoin 旧任务 + AudioTrack 重置（清空上一句残留静音帧）
     */
    fun speakWithPreGen(
        texts: List<String>,
        sid: Int,
        speed: Float,
        onComplete: (() -> Unit)? = null,
    ) {
        if (texts.isEmpty()) { onComplete?.invoke(); return }
        // ★ 自增代次、设 stopped、重置 track 为可写状态
        val gen = ++generation
        stopped = true
        val oldJob = speakJob
        speakJob = null
        // ★ 暂停 + 清空 AudioTrack 缓冲，避免上一句末尾静音帧混入新句开头
        track?.runCatching {
            pause(); flush(); play()
        }
        // 同步等待旧任务彻底退出（取消旧 Kokoro 推理、关闭 callback）
        speakJob = scope.launch {
            try {
                oldJob?.cancelAndJoin()
                // 双重检查：期间可能又调了一次 speakWithPreGen（代次已变）
                if (generation != gen) return@launch
                stopped = false
                AppLog.i("TTS", "speakWithPreGen 启动：${texts.size}句, gen=$gen")
                
                // ★ 关键：每句只播一次
                // i=0: 流式播放 + 启动句 1 预生成
                var preGen: Deferred<FloatArray?>? = null
                if (texts.size > 1) {
                    preGen = scope.async(Dispatchers.Default) {
                        generateSync(texts[1], sid, speed)
                    }
                }
                playOneStreaming(texts[0], sid, speed, gen)
                if (stopped || generation != gen) return@launch
                
                // i=1..n-1: await 预生成 + 同步播 + 启动下一句预生成
                for (i in 1 until texts.size) {
                    if (stopped || generation != gen) break
                    val samples = preGen?.await()
                    if (samples == null || samples.isEmpty()) break
                    // 启动下一句预生成（除最后一句）
                    preGen = if (i + 1 < texts.size) {
                        scope.async(Dispatchers.Default) {
                            generateSync(texts[i + 1], sid, speed)
                        }
                    } else null
                    // 同步播放当前句
                    playOneSync(samples, gen)
                    if (stopped || generation != gen) break
                }
            } catch (e: Throwable) {
                AppLog.e("TTS", "speakWithPreGen 失败", e)
            } finally {
                onComplete?.invoke()
            }
        }
    }

    /** 立即停止当前播报（含预生成）。
     *
     * 不 pause/flush/play（引入噪声 + 让后续 ensureTrack 需重启），
     * 仅让 callback 看到 stopped/代次不一致而退出，后续 speak 直接继续写同一 track。 */
    fun stop() {
        stopped = true
        generation++  // ★ 让旧任务全部失效
        speakJob?.cancel()
        speakJob = null
    }

    fun release() {
        stop()
        scope.cancel()
        track?.runCatching { stop(); release() }
        track = null
        tts.release()
    }
}
