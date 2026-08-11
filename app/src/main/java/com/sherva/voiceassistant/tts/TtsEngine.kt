package com.sherva.voiceassistant.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * TTS 引擎：封装 Kokoro int8 multi-lang（中英双语，103 音色）。
 *
 * 对齐 sherpa-onnx 官方 SherpaOnnxTts demo：
 *   - AudioTrack 在引擎构造后即创建并进入 play 状态（USAGE_MEDIA）
 *   - 用 generateWithConfigAndCallback + GenerationConfig(sid, speed, silenceScale) 让 sherpa 处理语速
 *   - 每次播报前 pause/flush/play（对齐官方 onClickGenerate），复用同一个 track
 *   - 流式 callback：边合成边写入 AudioTrack，首响低
 *
 * ★ 关键限制（ONNX Runtime）：
 *   - OfflineTts 持单一 native ptr（一个 ONNX session）
 *   - 并发调用 generate / generateWithCallback 会竞争 session，导致音频错乱
 *   - 因此 speak() 必须串行调用，不能预生成（除非用第二个 OfflineTts 实例）
 *
 * ★ Kokoro 配置注意：
 *   - maxNumSentences 对 Kokoro 无效（sherpa 源码注释明确）
 *   - silenceScale 应在 GenerationConfig 里设（per-call），不在 OfflineTtsConfig
 */
class TtsEngine(
    context: Context,
    private val numThreads: Int = 2,
) {
    companion object {
        private const val DIGITS = "0123456789"
        private const val CN_DIGITS = "零一二三四五六七八九"
        /**
         * 阿拉伯数字 → 中文数字（逐位替换）。
         * Kokoro espeak 默认逐位读英文 ("773" → "seven seven three")，
         * 中文场景下应读 "七七三"。
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
        AppLog.i("TTS", "构造 OfflineTts (Kokoro): model=${ModelPaths.TTS_MODEL}")
        // ★ Kokoro 的 espeak-ng-data 需要 POSIX 文件访问，必须先释放到 filesDir
        val espeakFilesDir = extractEspeakData(context)
        // ★ 精简配置：对齐官方 NonStreamingTtsKokoroZhEn.java
        //   - 不设 lengthScale（默认 1.0）
        //   - 不设 maxNumSentences（Kokoro 忽略）
        //   - 不在 OfflineTtsConfig 设 silenceScale（per-call 在 GenerationConfig 设）
        OfflineTts(
            assetManager = context.assets,
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = ModelPaths.TTS_MODEL,
                        voices = ModelPaths.TTS_VOICES,
                        tokens = ModelPaths.TTS_TOKENS,
                        lexicon = ModelPaths.TTS_LEXICON,
                        dataDir = espeakFilesDir.absolutePath,
                        lengthScale = 1.0f,
                    ),
                    numThreads = numThreads,
                    provider = "cpu",
                ),
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
        val assetPath = ModelPaths.TTS_DATA_DIR
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
            if (out.exists()) continue
            if (am.list(src)?.isNotEmpty() == true) {
                out.mkdirs()
                copyAssetDir(context, src, out)
            } else {
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
    /** 代次计数：每次新 speak 自增。旧任务的 callback 检查代次不一致就退出。 */
    @Volatile private var generation = 0
    private var speakJob: Job? = null

    // ★ 生产者-消费者解耦：sherpa 生成线程全速跑，播放线程匀速消费
    //   避免 callback 里 write BLOCKING 拖慢生成（RTF 从 ≈1 降到 ≈0.3）
    private val audioQueue = ArrayBlockingQueue<FloatArray>(64)  // 64 chunk ≈ 数十秒缓冲
    private var producerDone = false  // 生成是否完成（消费者用）

    /** 创建并启动 AudioTrack（持续 play，每次 speak 前 pause/flush/play 清空残留缓冲）。 */
    private fun ensureTrack(): AudioTrack {
        track?.let { return it }
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
        AppLog.i("TTS", "AudioTrack 创建并 play, sampleRate=$sampleRate")
        return t
    }

    fun isSpeaking() = speakJob?.isActive == true

    /**
     * 播报整段文本（生产者-消费者双 pipeline）。
     *
     * sherpa Kokoro 内部按 token 分 batch（batch_size=1），每个 batch Process 一次。
     * callback 在每个 batch 后触发，把音频入队（不阻塞生成线程）。
     *
     * ★ 并行原理：
     *   - 生产者：sherpa 生成线程全速跑（RTF≈0.3），比播放快 2-3 倍
     *   - 消费者：播放线程从队列取音频 write AudioTrack
     *   - 有界队列背压：队列满时生产者阻塞（不 OOM）
     *   - 首响延迟 = 第一个 batch 生成时间（~0.3s）
     *   - barge-in：stop() 清空队列 + cancel 两个线程 + flush AudioTrack
     */
    fun speak(
        text: String,
        sid: Int,
        speed: Float,
        onComplete: (() -> Unit)? = null,
    ) {
        if (text.isBlank()) { onComplete?.invoke(); return }
        val gen = ++generation
        stopped = true
        val oldJob = speakJob
        speakJob = null
        speakJob = scope.launch {
            try {
                oldJob?.cancelAndJoin()
                if (generation != gen) return@launch
                stopped = false
                producerDone = false
                audioQueue.clear()
                track?.runCatching { pause(); flush(); play() }
                val normalized = digitsToChinese(text)
                AppLog.i("TTS", "speak 启动：${normalized.length}字, gen=$gen")
                val t = ensureTrack()

                // ★ 消费者协程：出队 → AudioTrack
                val consumer = launch(Dispatchers.IO) {
                    try {
                        while (!stopped && generation == gen) {
                            val samples = audioQueue.poll(100, TimeUnit.MILLISECONDS)
                            if (samples != null) {
                                t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                            } else if (producerDone) {
                                break  // 生成完成 + 队列空 → 退出
                            }
                        }
                    } catch (e: Throwable) {
                        AppLog.e("TTS", "消费者异常", e)
                    }
                }

                // ★ 生产者：sherpa 生成 → 入队（当前协程，sherpa callback 在此线程调用）
                try {
                    tts.generateWithConfigAndCallback(
                        text = normalized,
                        config = GenerationConfig(sid = sid, speed = speed, silenceScale = 0.05f),
                        callback = { samples ->
                            if (stopped || generation != gen) return@generateWithConfigAndCallback 0
                            // ★ 入队（队列满则阻塞，自然背压）
                            audioQueue.put(samples)
                            return@generateWithConfigAndCallback 1
                        },
                    )
                } finally {
                    producerDone = true  // 通知消费者：生成完成
                }

                // 等消费者播完队列剩余
                consumer.join()
            } catch (e: Throwable) {
                AppLog.e("TTS", "speak 失败", e)
            } finally {
                onComplete?.invoke()
            }
        }
    }

    /** 立即停止当前播报（清空 AudioTrack 缓冲，避免残留继续播）。 */
    fun stop() {
        stopped = true
        generation++
        producerDone = true  // 让消费者不等待
        audioQueue.clear()   // ★ 清空队列，barge-in 立即生效
        speakJob?.cancel()
        speakJob = null
        track?.runCatching { pause(); flush(); play() }
    }

    fun release() {
        stop()
        scope.cancel()
        track?.runCatching { stop(); release() }
        track = null
        tts.release()
    }
}
