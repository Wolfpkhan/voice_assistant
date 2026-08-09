package com.sherva.voiceassistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.sherva.voiceassistant.R

/**
 * 语音助手音效播放器（提示音）。
 *
 * - [startListen]：模型就绪开始聆听时的提示音
 * - [interrupt]：用户打断 TTS 时的确认音
 *
 * 播放与 TTS 同用 STREAM_MUSIC/USAGE_ASSISTANT 流，
 * 不打断正在进行的 TTS（提示音叠加在音频流上）。
 */
object SoundEffects {

    @Volatile private var contextRef: Context? = null
    private var players = mutableListOf<MediaPlayer>()

    /** 初始化（Application/MainActivity onCreate 调用一次）。 */
    fun init(context: Context) {
        contextRef = context.applicationContext
    }

    /** 模型就绪开始聆听的提示音。 */
    fun startListen() = play(R.raw.sfx_start_listen)

    /** 被打断（用户打断 TTS）确认音。 */
    fun interrupt() = play(R.raw.sfx_interrupt)

    private fun play(rawId: Int) {
        val ctx = contextRef ?: return
        try {
            val attrs = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .build()
            val mp = MediaPlayer.create(ctx, rawId) ?: return
            mp.setAudioAttributes(attrs)
            mp.setOnCompletionListener {
                it.release()
                synchronized(players) { players.remove(it) }
            }
            synchronized(players) {
                players.add(mp)
                // 防止音效堆积
                if (players.size > 3) {
                    players.firstOrNull()?.runCatching { release() }
                    players.removeAt(0)
                }
            }
            mp.start()
        } catch (_: Throwable) {
        }
    }
}
