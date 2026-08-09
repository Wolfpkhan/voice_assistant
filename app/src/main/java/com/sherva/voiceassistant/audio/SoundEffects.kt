package com.sherva.voiceassistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.sherva.voiceassistant.R

/**
 * 语音助手音效播放器（提示音），基于 SoundPool（短音效低延迟、可靠）。
 *
 * - [startListen]：模型就绪开始聆听时的提示音
 * - [interrupt]：用户打断 TTS 时的确认音
 */
object SoundEffects {

    @Volatile private var pool: SoundPool? = null
    private var startId = 0
    private var interruptId = 0
    @Volatile private var ready = false

    /** 初始化（MainActivity onCreate 调用一次）。 */
    fun init(context: Context) {
        if (pool != null) return
        val attrs = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .build()
        val p = SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(attrs)
            .build()
        startId = p.load(context, R.raw.sfx_start_listen, 1)
        interruptId = p.load(context, R.raw.sfx_interrupt, 1)
        p.setOnLoadCompleteListener { _, _, _ -> ready = true }
        pool = p
    }

    /** 模型就绪开始聆听的提示音。 */
    fun startListen() {
        if (!ready) {
            // SoundPool 异步加载，未就绪则 200ms 后重试
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                play(startId)
            }, 200)
        } else {
            play(startId)
        }
    }

    /** 被打断（用户打断 TTS）确认音。 */
    fun interrupt() = play(interruptId)

    private fun play(id: Int) {
        if (id == 0) return
        runCatching {
            pool?.play(id, 1.0f, 1.0f, 1, 0, 1.0f)
        }
    }
}
