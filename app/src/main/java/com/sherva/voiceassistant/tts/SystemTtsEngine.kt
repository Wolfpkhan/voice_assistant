package com.sherva.voiceassistant.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.sherva.voiceassistant.AppLog
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 系统 TTS 引擎：基于 Android TextToSpeech。
 *
 * 优点：
 * - 首响延迟 <100ms（预加载即时合成）
 * - CPU 占用极低（<5%）
 * - 无需额外模型（省 207MB）
 *
 * 缺点：
 * - 音色取决于设备厂商引擎（华为/讯飞较好，原生 Google 一般）
 * - 无音色切换（sid 参数忽略）
 * - 中英混合可能跳读
 *
 * 语速映射：speed 1.0 = 系统 TTS 1.0（正常）。
 */
class SystemTtsEngine(context: Context) : TtsProvider {

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var pendingComplete: (() -> Unit)? = null
    private val utteranceCounter = AtomicLong(0)

    init {
        AppLog.i("SysTTS", "初始化系统 TTS...")
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val tts = this.tts
                if (tts != null) {
                    // 设中文优先，引擎会自动 fallback 英文
                    val res = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
                    AppLog.i("SysTTS", "系统 TTS 就绪, 语言设置结果=$res, engine=${tts.defaultEngine}")
                    // 监听播放进度
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) {
                            AppLog.i("SysTTS", "播放完成")
                            val cb = pendingComplete
                            pendingComplete = null
                            cb?.invoke()
                        }
                        override fun onError(id: String?) {
                            AppLog.w("SysTTS", "播放出错: $id")
                            val cb = pendingComplete
                            pendingComplete = null
                            cb?.invoke()
                        }
                    })
                    ready = true
                }
            } else {
                AppLog.e("SysTTS", "系统 TTS 初始化失败: status=$status")
            }
        }
    }

    override fun speak(text: String, sid: Int, speed: Float, onComplete: (() -> Unit)?) {
        if (text.isBlank()) { onComplete?.invoke(); return }
        if (!ready) {
            AppLog.w("SysTTS", "系统 TTS 未就绪，跳过")
            onComplete?.invoke()
            return
        }
        pendingComplete = onComplete
        // 语速映射：Kokoro speed 1.0 = 正常；系统 TTS 1.0 = 正常，范围 0~4（0.5慢~2.0快）
        val rate = (speed * 1.0f).coerceIn(0.1f, 4.0f)
        tts?.setSpeechRate(rate)
        val id = "utt_${utteranceCounter.incrementAndGet()}"
        AppLog.i("SysTTS", "播报: ${text.length}字, rate=$rate")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    override fun stop() {
        tts?.stop()
        val cb = pendingComplete
        pendingComplete = null
        cb?.invoke()
        AppLog.i("SysTTS", "stop()")
    }

    override fun isSpeaking(): Boolean = tts?.isSpeaking == true

    override fun release() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
        AppLog.i("SysTTS", "release()")
    }
}
