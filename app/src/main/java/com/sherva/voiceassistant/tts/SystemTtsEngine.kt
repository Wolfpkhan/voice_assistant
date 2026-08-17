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
 */
class SystemTtsEngine(context: Context) : TtsProvider {

    private val appContext: Context = context.applicationContext
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var pendingComplete: (() -> Unit)? = null
    @Volatile private var pendingText: String? = null
    @Volatile private var pendingSpeed: Float = 1.0f
    private val utteranceCounter = AtomicLong()

    init {
        AppLog.i("SysTTS", "初始化系统 TTS...")
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val tts = this.tts
                if (tts != null) {
                    val res = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
                    AppLog.i("SysTTS", "系统 TTS 就绪, 语言设置结果=$res, engine=${tts.defaultEngine}")
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
                    // ★ 如果初始化前已有 pending 播报，现在执行
                    val text = pendingText
                    val speed = pendingSpeed
                    val cb = pendingComplete
                    pendingText = null
                    pendingComplete = null
                    if (text != null && text.isNotBlank()) {
                        AppLog.i("SysTTS", "引擎就绪后补播: ${text.length}字")
                        doSpeak(text, speed, cb)
                    }
                }
            } else {
                AppLog.e("SysTTS", "系统 TTS 初始化失败: status=$status")
            }
        }
    }

    override fun speak(text: String, sid: Int, speed: Float, onComplete: (() -> Unit)?) {
        if (text.isBlank()) { onComplete?.invoke(); return }
        if (!ready) {
            // ★ 引擎未就绪：存 pending，等 init 回调执行
            AppLog.i("SysTTS", "系统 TTS 未就绪，等待初始化完成后补播")
            pendingText = text
            pendingSpeed = speed
            pendingComplete = onComplete
            return
        }
        doSpeak(text, speed, onComplete)
    }

    private fun doSpeak(text: String, speed: Float, onComplete: (() -> Unit)?) {
        pendingComplete = onComplete
        val rate = speed.coerceIn(0.1f, 4.0f)
        tts?.setSpeechRate(rate)
        // ★ 蓝牙耳机：SCO 激活时才把 TTS 流切为通话用途（USAGE_VOICE_COMMUNICATION）
        //   → 路由跟随 communicationDevice（耳机 HFP 双向）。
        //   SCO 关闭时不设置任何 attributes（保持引擎默认 MEDIA 流）——
        //   真机踩坑：设 USAGE_ASSISTANT 会被 vivo 路由到听筒/超低音量
        //   （STREAM_ASSISTANT 是语音助手专用流），车载 A2DP 场景 TTS 无声。
        try {
            val scoOn = com.sherva.voiceassistant.audio.ScoAudioRouter.isConnected(appContext)
            if (scoOn) {
                val attr = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(attr)
                AppLog.i("SysTTS", "SCO 激活 → TTS 流切为通话用途（路由到耳机）")
            }
            // SCO 关闭：不动 attributes，引擎默认 MEDIA 流走 A2DP/扬声器
        } catch (e: Throwable) {
            AppLog.i("SysTTS", "setAudioAttributes 异常: ${e.message}")
        }
        val id = "utt_${utteranceCounter.incrementAndGet()}"
        AppLog.i("SysTTS", "播报: ${text.length}字, rate=$rate")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    override fun stop() {
        tts?.stop()
        val cb = pendingComplete
        pendingComplete = null
        pendingText = null
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
