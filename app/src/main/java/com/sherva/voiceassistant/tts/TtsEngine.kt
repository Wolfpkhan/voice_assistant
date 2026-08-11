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
        private const val CN_DIGITS = "零一二三四五六七八九"

        /**
         * 智能数字→中文读法（场景化）：
         * - 年份（4位+年）：逐位读 "二零二四年"
         * - 时间 HH:MM：N点M分
         * - 小数 N.NN：N点逐位
         * - 月份/日期/号：按数值读 "八月"/"十一日"
         * - 百分比：百分之N
         * - 温度：N摄氏度/N度
         * - 一般数字：中文大数读法 "一百二十三"
         */
        fun digitsToChinese(text: String): String {
            var r = text
            // 1. 年份（4位+年）：逐位读
            r = Regex("([0-9]{4})年").replace(r) {
                it.groupValues[1].map { c -> CN_DIGITS[c - '0'] }.joinToString("") + "年"
            }
            // 2. 时间 HH:MM → N点M分
            r = Regex("([0-9]{1,2}):([0-9]{2})").replace(r) {
                val h = it.groupValues[1].toLong()
                val m = it.groupValues[2].toLong()
                if (m == 0L) "${numToZh(h)}点" else "${numToZh(h)}点${numToZh(m)}分"
            }
            // 2b. N点（如 20点 → 二十点，21点 → 二十一点）
            r = Regex("([0-9]{1,2})点").replace(r) { "${numToZh(it.groupValues[1].toLong())}点" }
            // 2c. N分（如 30分 → 三十分）
            r = Regex("([0-9]{1,2})分").replace(r) { "${numToZh(it.groupValues[1].toLong())}分" }
            // 3. 小数 N.NN → N点逐位
            r = Regex("([0-9]+)\\.([0-9]+)").replace(r) {
                val i = it.groupValues[1].toLong()
                val f = it.groupValues[2]
                "${numToZh(i)}点${f.map { c -> CN_DIGITS[c - '0'] }.joinToString("")}"
            }
            // 4. 月份/日期/号：按数值读
            r = Regex("([0-9]{1,2})月").replace(r) { "${numToZh(it.groupValues[1].toLong())}月" }
            r = Regex("([0-9]{1,2})日").replace(r) { "${numToZh(it.groupValues[1].toLong())}日" }
            r = Regex("([0-9]{1,2})号").replace(r) { "${numToZh(it.groupValues[1].toLong())}号" }
            // 5. 温度 °C/℃ → 摄氏度（优先于单独 °）
            r = Regex("([0-9]+)(?:°C|℃)").replace(r) { "${numToZh(it.groupValues[1].toLong())}摄氏度" }
            // 6. 百分比
            r = Regex("([0-9]+)%").replace(r) { "百分之${numToZh(it.groupValues[1].toLong())}" }
            // 7. 金额（优先级最高，先处理）
            //   ¥/￥/$N 或 N元 → N元（内部按数值读）
            r = Regex("[¥￥\$]([0-9]+(?:\\.[0-9]+)?)").replace(r) {
                val numStr = it.groupValues[1]
                if ("." in numStr) {
                    val parts = numStr.split(".")
                    val zh = numToZh(parts[0].toLong()) + "点" +
                        parts[1].map { c -> CN_DIGITS[c - '0'] }.joinToString("")
                    zh + "元"
                } else {
                    numToZh(numStr.toLong()) + "元"
                }
            }
            r = Regex("([0-9]+(?:\\.[0-9]+)?)元").replace(r) {
                val numStr = it.groupValues[1]
                if ("." in numStr) {
                    val parts = numStr.split(".")
                    numToZh(parts[0].toLong()) + "点" +
                        parts[1].map { c -> CN_DIGITS[c - '0'] }.joinToString("") + "元"
                } else {
                    numToZh(numStr.toLong()) + "元"
                }
            }
            // 7. 温度 ° → 度
            r = Regex("([0-9]+)°").replace(r) { "${numToZh(it.groupValues[1].toLong())}度" }
            // 8. 剩余数字：按数值读
            r = Regex("[0-9]+").replace(r) {
                it.value.toLongOrNull()?.let(::numToZh) ?: it.value
            }
            return r
        }

        /** 整数→中文读法（按数值，如 123→一百二十三）。 */
        private fun numToZh(n: Long): String {
            if (n == 0L) return "零"
            if (n < 0) return "负" + numToZh(-n)
            val sb = StringBuilder()
            if (n >= 100000000L) {
                sb.append(numToZh(n / 100000000)).append("亿")
                val rem = n % 100000000
                if (rem > 0) {
                    if (rem < 10000000) sb.append("零")
                    sb.append(leadingYi(rem))
                }
                return sb.toString()
            }
            if (n >= 10000L) {
                sb.append(numToZh(n / 10000)).append("万")
                val rem = n % 10000
                if (rem > 0) {
                    if (rem < 1000) sb.append("零")
                    sb.append(leadingYi(rem))
                }
                return sb.toString()
            }
            if (n >= 1000L) {
                sb.append(CN_DIGITS[(n / 1000).toInt()]).append("千")
                val rem = n % 1000
                if (rem > 0) {
                    if (rem < 100) sb.append("零")
                    sb.append(leadingYi(rem))
                }
                return sb.toString()
            }
            if (n >= 100L) {
                sb.append(CN_DIGITS[(n / 100).toInt()]).append("百")
                val rem = n % 100
                if (rem > 0) {
                    if (rem < 10) sb.append("零")
                    sb.append(leadingYi(rem))
                }
                return sb.toString()
            }
            if (n >= 10L) {
                val tens = (n / 10).toInt()
                val ones = (n % 10).toInt()
                if (tens == 1) sb.append("十") else sb.append(CN_DIGITS[tens]).append("十")
                if (ones > 0) sb.append(CN_DIGITS[ones])
                return sb.toString()
            }
            return CN_DIGITS[n.toInt()].toString()
        }

        /** 跟在高位后面的读法（10-19 补 “一”，如 110→一百一十）。 */
        private fun leadingYi(n: Long): String {
            if (n in 10..19) return "一" + numToZh(n)
            return numToZh(n)
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
    // ★ 进度统计（每次 speak 重置）
    @Volatile private var callbackCount = 0
    @Volatile private var consumerWriteCount = 0
    @Volatile private var speakStartTime = 0L

    /** 创建并启动 AudioTrack（持续 play，每次 speak 前 pause/flush/play 清空残留缓冲）。 */
    private fun ensureTrack(): AudioTrack {
        track?.let { existing ->
            // ★ 如果 track 不在 PLAYING 状态，重新 play
            //   stop() 后或 pause() 后都走这里恢复
            if (existing.playState != AudioTrack.PLAYSTATE_PLAYING) {
                existing.play()
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
        AppLog.i("TTS", "AudioTrack 创建并 play, sampleRate=$sampleRate")
        return t
    }

    fun isSpeaking() = speakJob?.isActive == true

    /** ★ 仅生成不播放（用于音色预览）。 */
    fun generateSync(text: String, sid: Int, speed: Float): Pair<FloatArray, Int>? {
        if (text.isBlank()) return null
        val normalized = digitsToChinese(text)
        return runCatching {
            val audio = tts.generateWithConfig(
                normalized,
                GenerationConfig(sid = sid, speed = speed, silenceScale = 0.2f)
            )
            Pair(audio.samples, audio.sampleRate)
        }.onFailure { AppLog.e("TTS", "generateSync 失败", it) }.getOrNull()
    }

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
                callbackCount = 0
                consumerWriteCount = 0
                speakStartTime = System.currentTimeMillis()
                // ★ 只 pause+flush 清空旧缓冲，不 play（让 ensureTrack 统一恢复）
                //   play() 后立即写短音频会丢失开头 → 哒哒声
                track?.runCatching { pause(); flush() }
                val normalized = digitsToChinese(text)
                AppLog.i("TTS", "speak 启动：${normalized.length}字, gen=$gen")
                val t = ensureTrack()  // 这里会检测 STOPPED 并 play()

                // ★ 消费者协程：出队 → AudioTrack
                val consumer = launch(Dispatchers.IO) {
                    try {
                        while (!stopped && generation == gen) {
                            val samples = audioQueue.poll(100, TimeUnit.MILLISECONDS)
                            if (samples != null) {
                                t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                                consumerWriteCount++
                            } else if (producerDone) {
                                break  // 生成完成 + 队列空 → 退出
                            }
                        }
                        // ★ 等 AudioTrack 真正播完缓冲区剩余数据
                        //   write(BLOCKING) 只等写入缓冲区，不等播放
                        //   不等的话后续 pause/flush 会冲掉未播音频 → 哒哒声/没声音
                        if (!stopped && generation == gen) {
                            drainAudioTrack(t)
                        }
                    } catch (e: CancellationException) {
                        // cancel 是正常的，不打错误日志
                    } catch (e: Throwable) {
                        AppLog.e("TTS", "消费者异常", e)
                    }
                }

                // ★ 生产者：sherpa 生成 → 入队（当前协程，sherpa callback 在此线程调用）
                try {
                    tts.generateWithConfigAndCallback(
                        text = normalized,
                        config = GenerationConfig(sid = sid, speed = speed, silenceScale = 0.2f),
                        callback = { samples ->
                            if (stopped || generation != gen) return@generateWithConfigAndCallback 0
                            callbackCount++
                            // ★ 每 5 个 callback 打一次进度 log
                            if (callbackCount % 5 == 1) {
                                val elapsed = (System.currentTimeMillis() - speakStartTime) / 1000f
                                val qSize = audioQueue.size
                                AppLog.i("TTS", "进度: callback=$callbackCount consumer=$consumerWriteCount 队列=$qSize 耗时=${elapsed}s")
                            }
                            // ★ 入队（带超时，不永久阻塞）
                            //   队列满时每 50ms 检查一次 stopped，让 barge-in 能生效
                            //   之前用 put() 会永久阻塞 callback → sherpa 无法停止
                            while (!audioQueue.offer(samples, 50, TimeUnit.MILLISECONDS)) {
                                if (stopped || generation != gen) return@generateWithConfigAndCallback 0
                            }
                            return@generateWithConfigAndCallback 1
                        },
                    )
                    val genElapsed = (System.currentTimeMillis() - speakStartTime) / 1000f
                    AppLog.i("TTS", "★ 生成完成: callback=$callbackCount, 耗时=${genElapsed}s")
                } finally {
                    producerDone = true  // 通知消费者：生成完成
                }

                // 等消费者播完队列剩余
                consumer.join()
                val totalElapsed = (System.currentTimeMillis() - speakStartTime) / 1000f
                AppLog.i("TTS", "★ 播放完成: 总耗时=${totalElapsed}s callback=$callbackCount consumer=$consumerWriteCount")
            } catch (e: Throwable) {
                AppLog.e("TTS", "speak 失败", e)
            } finally {
                onComplete?.invoke()
            }
        }
    }

    /** ★ 等 AudioTrack 播完缓冲区剩余数据。
     *   playbackHeadPosition 在 flush 后会重置为 0 且可能延迟更新，
     *   先等 50ms 让 AudioTrack 启动播放，再轮询 position 不再增长。 */
    private fun drainAudioTrack(t: AudioTrack) {
        try {
            val timeoutMs = 5000L  // 最多等 5 秒
            val startMs = System.currentTimeMillis()
            // ★ flush 后 head position 可能短暂停在 0，先给 AudioTrack 启动时间
            Thread.sleep(50)
            var lastPos = t.playbackHeadPosition
            var stableCount = 0
            while (System.currentTimeMillis() - startMs < timeoutMs) {
                Thread.sleep(20)
                val pos = t.playbackHeadPosition
                if (pos > lastPos) {
                    stableCount = 0
                    lastPos = pos
                } else {
                    stableCount++
                    // head position 连续 100ms(5次) 不变 = 播完了
                    if (stableCount >= 5) break
                }
            }
        } catch (_: Throwable) {}
    }

    /** 立即停止当前播报（清空 AudioTrack 缓冲，避免残留继续播）。
     *
     * ★ 只 pause+flush，不 play —— 让 AudioTrack 真正静音
     *   play() 会让消费者协程继续写入并播放
     *   下次 speak() 时 ensureTrack 会自动恢复 play */
    fun stop() {
        stopped = true
        generation++
        producerDone = true  // 让消费者不等待
        audioQueue.clear()   // ★ 清空队列，barge-in 立即生效
        speakJob?.cancel()
        speakJob = null
        // ★ 只 pause+flush，不 play —— 用户立即听到静音
        track?.runCatching { pause(); flush() }
        AppLog.i("TTS", "stop() 已执行：AudioTrack paused+flushed")
    }

    fun release() {
        stop()
        scope.cancel()
        track?.runCatching { stop(); release() }
        track = null
        tts.release()
    }
}
