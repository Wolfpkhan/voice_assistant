package com.sherva.voiceassistant.pipeline

import android.content.Context
import android.util.Log
import com.sherva.voiceassistant.AppLog
import com.sherva.voiceassistant.asr.StreamingAsrEngine
import kotlinx.coroutines.delay
import com.sherva.voiceassistant.llm.LlmClient
import com.sherva.voiceassistant.tts.TtsEngine
import com.sherva.voiceassistant.vad.BargeInDetector
import kotlinx.coroutines.*

/**
 * 语音助手大脑（流式版）：StreamingASR → 云端 LLM(流式) → TTS(分句边收边播)。
 *
 * ★ 升级点：用 OnlineRecognizer 流式识别，替代旧的「VAD + 离线ASR」：
 *   - 边说边出字，结果连续纠正（partial result 实时回调）
 *   - ASR 内置端点检测，无需独立 silero VAD
 *
 * 状态流转：
 *   IDLE → LISTENING[流式ASR实时出字] →[端点命中]→ THINKING[LLM]
 *        →[LLM 流式 token]→ SPEAKING[TTS] →[播完冷却]→ LISTENING(连续) 或 IDLE
 *
 * 防回声：TTS 播报期间停止 ASR 录音；播完冷却后再恢复监听。
 */
class VoiceAssistant(
    context: Context,
    val config: Config,
    private val listener: Listener,
) {
    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    data class Config(
        val continuous: Boolean = true,       // 连续对话：答完自动继续聆听
        val ttsSpeed: Float = 1.0f,
        val llmBaseUrl: String,
        val llmApiKey: String,
        val llmModel: String,
        val systemPrompt: String,
        // —— 高级时间参数（从设置页读取）——
        val cooldownMs: Long = 600L,              // 播报后冷却（防回声）
        val endpointTrailingSilenceSec: Float = 1.2f,  // 说完判定延时
        val bargeGuardMs: Long = 300L,           // 打断起播保护期
        val bargeConfirmMs: Long = 200L,         // 打断确认时长
        val bargeThreshold: Float = 0.6f,        // 打断 VAD 阈值
    )

    interface Listener {
        fun onState(state: State)
        /** 流式实时识别文本（连续纠正更新），UI 直接覆盖显示。 */
        fun onPartialText(text: String) {}
        /** 一句话最终文本（端点命中）。 */
        fun onUserText(text: String)
        fun onAssistantDelta(delta: String)
        fun onAssistantComplete(text: String)
        fun onError(message: String)
        /** 进入深度思考阶段（reasoning 模型）。 */
        fun onReasoningStart() {}
    }

    companion object {
        private const val TAG = "VoiceAssistant"
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // ★ 引擎 lazy 加载：首次使用才初始化（加载模型），避免构造阻塞 UI。
    //   文字模式只用 LLM，不触发 asr/tts/bargeIn 加载，发送不卡顿。
    private val asrLazy: Lazy<StreamingAsrEngine> = lazy {
        AppLog.i("VA", "初始化流式 ASR 引擎...")
        StreamingAsrEngine(appContext, endpointTrailingSilenceSec = config.endpointTrailingSilenceSec)
    }
    private val ttsLazy: Lazy<TtsEngine> = lazy { AppLog.i("VA", "初始化 TTS 引擎..."); TtsEngine(appContext) }
    private val bargeInLazy: Lazy<BargeInDetector> = lazy {
        AppLog.i("VA", "初始化打断检测器...")
        BargeInDetector(appContext, threshold = config.bargeThreshold,
            startGuardMs = config.bargeGuardMs, minSpeechMs = config.bargeConfirmMs)
    }
    private val asr: StreamingAsrEngine get() = asrLazy.value
    private val tts: TtsEngine get() = ttsLazy.value
    private val bargeIn: BargeInDetector get() = bargeInLazy.value
    private val llm = LlmClient(
        baseUrl = config.llmBaseUrl,
        apiKey = config.llmApiKey,
        model = config.llmModel,
    )
    private val history = mutableListOf<LlmClient.Message>()
    private var convJob: Job? = null
    @Volatile private var active = false   // 是否处于一轮对话中（防重入）
    @Volatile private var interrupted = false  // 本轮是否被用户打断（打断后跳过剩余 TTS）
    /** 文字模式标志：文字模式发送后不自动重新聆听（保持静默等下一轮输入）。 */
    @Volatile var textMode = false
    private var historyInitialized = false

    @Volatile var state: State = State.IDLE
        private set

    private fun setState(s: State) {
        state = s
        listener.onState(s)
    }

    /** 初始化对话历史（system prompt）。语音/文字共用。 */
    private fun ensureHistory() {
        if (historyInitialized) return
        history.clear()
        if (config.systemPrompt.isNotBlank()) {
            history += LlmClient.Message.system(config.systemPrompt)
        }
        historyInitialized = true
    }

    /** 启动对话循环（语音模式：开始聆听）。 */
    fun startConversation() {
        if (state != State.IDLE) return
        ensureHistory()
        scope.launch { startListening() }
    }

    private fun startListening() {
        // ★ 先确保 ASR 模型已就绪（首次 lazy 加载耗时 ~3s），再进入聆听态+音效
        AppLog.i("VA", "准备聆听（加载 ASR 模型）")
        scope.launch {
            withContext(Dispatchers.Default) {
                asr   // 触发 lazy 加载
            }
            setState(State.LISTENING)
            active = false
            AppLog.i("VA", "模型就绪，开始聆听")
            // 模型就绪开始聆听的提示音
            com.sherva.voiceassistant.audio.SoundEffects.startListen()
            asr.start(
                onPartial = { partial -> listener.onPartialText(partial) },
                onFinal = { final -> onFinalText(final) },
            )
        }
    }

    /** 端点命中：拿到完整一句话 → 停 ASR → LLM → TTS。 */
    private fun onFinalText(text: String) {
        if (active) return   // 防重入
        active = true
        scope.launch {
            AppLog.i("VA", "收到 final: \"$text\"")
            asr.stop()
            listener.onUserText(text)
            history += LlmClient.Message.user(text)
            handleLlmTurn()
        }
    }

    /**
     * ★ 文字输入消息：不经过语音，直接把文本送入 LLM 链路（复用同一逻辑）。
     * 用于聊天记录里点某条消息继续提问、或界面文字输入框发送。
     * 不会开启语音侦听。
     */
    fun sendText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (active) {
            AppLog.w("VA", "对话进行中，忽略 sendText")
            return
        }
        ensureHistory()
        active = true
        scope.launch {
            AppLog.i("VA", "文字输入: \"$t\"")
            // 若语音引擎已初始化（此前切过语音模式）则停掉聆听，避免冲突
            if (asrLazy.isInitialized()) asr.stop()
            listener.onUserText(t)
            history += LlmClient.Message.user(t)
            handleLlmTurn()
        }
    }

    /** LLM 流式 → TTS 播报（共用逻辑）。 */
    private suspend fun handleLlmTurn() {
        // LLM 流式（收集完整回复）
        setState(State.THINKING)
        val reply = StringBuilder()
        val startTime = System.currentTimeMillis()
        var reasoningSeen = false
        try {
            llm.chat(history,
                onToken = { delta ->
                    reply.append(delta)
                    listener.onAssistantDelta(delta)
                },
                onReasoning = {
                    if (!reasoningSeen) { reasoningSeen = true; listener.onReasoningStart() }
                },
            ).collect { full ->
                if (full.length > reply.length) reply.clear().append(full)
            }
        } catch (e: Throwable) {
            listener.onError("LLM 调用失败: ${e.message}")
        }
        val fullReply = reply.toString().trim()
        AppLog.i("VA", "LLM 完成: reasoning=$reasoningSeen, 回复 ${fullReply.length} 字: \"${fullReply.take(50)}\"")
        if (fullReply.isNotEmpty()) {
            listener.onAssistantComplete(fullReply)
            history += LlmClient.Message.assistant(fullReply)

            if (textMode) {
                // 文字模式：不播报 TTS，只显示文字
                AppLog.i("VA", "文字模式，跳过 TTS 播报")
            } else {
                // TTS 播报：分句串行播放（不再依赖流式切句，避免 reasoning 模型 content 延迟漏播）
                setState(State.SPEAKING)
                interrupted = false
                for (sentence in splitSentences(fullReply)) {
                    if (!kotlinx.coroutines.currentCoroutineContext().isActive || interrupted || state == State.IDLE) break
                    speakSentence(sentence)
                }
            }
        }
        Log.i(TAG, "本轮耗时 ${System.currentTimeMillis() - startTime}ms")
        val wasInterrupted = interrupted
        AppLog.i("VA", "本轮完成" + if (wasInterrupted) "（被用户打断）" else "")

        if (config.continuous && state != State.IDLE) {
            // 文字模式：不自动重新聆听，等用户下次输入
            if (textMode) {
                AppLog.i("VA", "文字模式，保持待听（不自动开启聆听）")
                active = false   // ★ 复位，允许下次文字输入
                setState(State.IDLE)
            } else {
                // 打断后立即重新聆听（不等冷却）；正常播完才冷却防回声
                if (!wasInterrupted) {
                    AppLog.i("VA", "冷却 ${config.cooldownMs}ms")
                    delay(config.cooldownMs)
                } else {
                    AppLog.i("VA", "打断恢复，立即重新聆听")
                }
                active = false   // 复位，准备下一轮
                startListening()
            }
        } else {
            active = false   // ★ 复位
            setState(State.IDLE)
        }
    }

    private suspend fun speakSentence(sentence: String) {
        if (sentence.isBlank()) return
        AppLog.i("VA", "调用 TTS 播报: \"${sentence.take(40)}\"")
        // ★ 打断支持：TTS 播报期间开启 Barge-in 检测，用户一开口就停
        bargeIn.start(onInterrupt = {
            AppLog.i("VA", "打断触发 → 停 TTS，跳过剩余播报")
            // 被打断确认音
            com.sherva.voiceassistant.audio.SoundEffects.interrupt()
            interrupted = true
            tts.stop()
            llm.cancel()
        })
        suspendCancellableCoroutine<Unit> { cont ->
            tts.speak(
                text = sentence,
                speed = config.ttsSpeed,
                onComplete = { if (cont.isActive) cont.resume(Unit) { } },
            )
            cont.invokeOnCancellation { tts.stop() }
        }
        bargeIn.stop()
    }

    fun stop() {
        convJob?.cancel()
        convJob = null
        active = false
        interrupted = true
        // 只停已初始化的引擎（避免 lazy 触发加载）
        if (bargeInLazy.isInitialized()) bargeIn.stop()
        if (asrLazy.isInitialized()) asr.stop()
        llm.cancel()
        if (ttsLazy.isInitialized()) tts.stop()
        setState(State.IDLE)
    }

    /** ★ 手动中断输出：只取消当前 LLM 生成（不再出新文字），TTS 可继续播已生成的。 */
    fun interruptOutput() {
        AppLog.i("VA", "手动中断 LLM 输出")
        interrupted = true   // 阻止剩余句子进入 TTS
        llm.cancel()
    }

    /** ★ 手动终止音频播放：只停 TTS（打断当前播报），LLM 若还在跑继续跑完。 */
    fun stopPlayback() {
        AppLog.i("VA", "手动终止 TTS 播放")
        interrupted = true
        if (bargeInLazy.isInitialized()) bargeIn.stop()
        if (ttsLazy.isInitialized()) tts.stop()
    }

    fun release() {
        stop()
        scope.cancel()
        if (asrLazy.isInitialized()) runCatching { asr.release() }
        if (ttsLazy.isInitialized()) runCatching { tts.release() }
        if (bargeInLazy.isInitialized()) runCatching { bargeIn.release() }
    }

    // ---------- 分句工具 ----------
    private val STRONG_END = charArrayOf('。', '！', '？', '!', '?', '；', ';', '\n', '…')
    /** TTS 不该念出的符号/markdown/emoji，合成前剔除（界面展示保留原文）。 */
    private val TTS_STRIP = charArrayOf('*', '#', '`', '~', '_', '-', '·', '•', '|', '/', '【', '】', '[', ']', '(', ')', '《', '》', '"', '\'', ':', '=', '>')

    /** 将完整回复拆分为句子（用于 TTS 串行播报，并净化为口语友好）。 */
    private fun splitSentences(text: String): List<String> {
        // 1. 先净化：去 markdown/符号/emoji/多余空白
        val cleaned = StringBuilder()
        for (ch in text) {
            if (ch in TTS_STRIP) continue   // 跳过 markdown 标记
            if (Character.isSurrogate(ch)) continue   // 跳过 emoji（代理对）
            if (ch == '\n') { cleaned.append('。'); continue }  // 换行→句号
            cleaned.append(ch)
        }
        // 合并空格并去首尾
        val safe = cleaned.toString().replace(Regex("[ \t]+"), " ").replace(Regex("[。]{2,}"), "。").trim()
        // 2. 再分句
        val result = mutableListOf<String>()
        val buf = StringBuilder()
        for (ch in safe) {
            buf.append(ch)
            if (ch in STRONG_END) {
                val s = buf.toString().trim()
                if (s.isNotEmpty()) result.add(s)
                buf.clear()
            }
        }
        val tail = buf.toString().trim()
        if (tail.isNotEmpty()) result.add(tail)
        return result
    }
}
