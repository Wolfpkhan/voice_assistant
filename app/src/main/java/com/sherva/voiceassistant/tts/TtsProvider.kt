package com.sherva.voiceassistant.tts

/**
 * TTS 引擎统一接口。
 *
 * 两个实现：
 * - [TtsEngine]：Kokoro int8 神经声学模型，音质好但慢（RTF ~0.8）
 * - [SystemTtsEngine]：Android 系统 TextToSpeech，极速（<100ms）但音色一般
 *
 * 切换由 [com.sherva.voiceassistant.pipeline.VoiceAssistant.Config.ttsEngine] 控制。
 */
interface TtsProvider {
    /**
     * 播报文本。
     * @param sid 音色 ID（仅 Kokoro 生效；系统 TTS 忽略）
     * @param speed 语速（1.0 = 正常）
     * @param onComplete 播放完成后回调（barge-in 停止时也会触发）
     */
    fun speak(text: String, sid: Int, speed: Float, onComplete: (() -> Unit)? = null)

    /** 立即停止当前播报。 */
    fun stop()

    /** 是否正在播报。 */
    fun isSpeaking(): Boolean

    /** 释放资源。 */
    fun release()
}
