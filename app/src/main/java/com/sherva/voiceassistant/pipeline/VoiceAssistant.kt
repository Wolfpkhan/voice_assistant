package com.sherva.voiceassistant.pipeline

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.sherva.voiceassistant.AppLog
import com.sherva.voiceassistant.asr.KeywordSpotterEngine
import com.sherva.voiceassistant.asr.StreamingAsrEngine
import kotlinx.coroutines.delay
import com.sherva.voiceassistant.llm.LlmClient
import com.sherva.voiceassistant.tts.TtsProvider
import com.sherva.voiceassistant.vad.BargeInDetector
import kotlinx.coroutines.*
import kotlin.coroutines.resume

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
) {
    enum class State { IDLE, LISTENING, THINKING, SPEAKING, WAKE_WORD }

    /** 空 listener 默认实现，避免构造时必须传。 */
    private val defaultListener = object : Listener {
        override fun onState(state: State) {}
        override fun onUserText(text: String) {}
        override fun onAssistantDelta(delta: String) {}
        override fun onAssistantComplete(text: String) {}
        override fun onError(message: String) {}
    }

    /**
     * ★ 多 listener 广播机制：
     *  所有回调都会同步触发 `listeners` 列表里所有 listener。
     *  Activity 与 Service 都调用 addListener 加入接收，避免互相挤兑。
     */
    private val listeners = mutableListOf<Listener>()
    @Volatile var listener: Listener = defaultListener
        private set

    data class Config(
        val continuous: Boolean = true,       // 连续对话：答完自动继续聆听
        val ttsSpeed: Float = 1.0f,
        val ttsSid: Int = 3,                    // 默认中文女声 zf_001
        val ttsEngine: String = "kokoro",      // "kokoro" | "system"
        val llmBaseUrl: String,
        val llmApiKey: String,
        val llmModel: String,
        val systemPrompt: String,                 // 语音模式系统提示词
        val systemPromptText: String = systemPrompt,  // 文字模式系统提示词（默认同语音）
        // —— 高级时间参数（从设置页读取）——
        val cooldownMs: Long = 600L,              // 播报后冷却（防回声）
        val endpointTrailingSilenceSec: Float = 1.2f,  // 说完判定延时
        val bargeGuardMs: Long = 300L,           // 打断起播保护期
        val bargeConfirmMs: Long = 200L,         // 打断确认时长
        val bargeThreshold: Float = 0.6f,        // 打断 VAD 阈值
        val enableBargeIn: Boolean = false,     // 默认关闭（Kokoro 容易自打断，需手动开）
        val micGain: Float = 1.0f,               // 麦克风增益（远距离收音）
        // —— 唤醒词参数（从设置页读取）——
        val enableWakeWord: Boolean = true,           // 是否启用唤醒词模式（语音模式下始终启用）
        val wakeWordIdleSec: Float = 5.0f,           // 进入唤醒模式的空闲时间（秒）：ASR 启动后 X 秒无有效语音 → KWS
        val wakeWord: String = "小薇",           // 唤醒词（可配置，推荐 2~4 字短词，KWS 小模型对长词识别率低）
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
    // ★ Wake Lock：语音会话期间保持 CPU 唤醒，息屏不中断录音/播放
    private val wakeLock: PowerManager.WakeLock by lazy {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SherpaVoice::session").apply {
            setReferenceCounted(false)
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // ★ 引擎 lazy 加载：首次使用才初始化（加载模型），避免构造阻塞 UI。
    //   文字模式只用 LLM，不触发 asr/tts/bargeIn 加载，发送不卡顿。
    private val asrLazy: Lazy<StreamingAsrEngine> = lazy {
        AppLog.i("VA", "初始化流式 ASR 引擎...")
        StreamingAsrEngine(appContext, endpointTrailingSilenceSec = config.endpointTrailingSilenceSec, micGain = config.micGain)
    }
    private val kwsLazy: Lazy<KeywordSpotterEngine> = lazy {
        AppLog.i("VA", "初始化唤醒词检测引擎...")
        KeywordSpotterEngine(appContext)
    }
    private val ttsLazy: Lazy<TtsProvider> = lazy {
        AppLog.i("VA", "初始化 TTS 引擎: ${config.ttsEngine}")
        if (config.ttsEngine == "system") {
            com.sherva.voiceassistant.tts.SystemTtsEngine(appContext)
        } else {
            com.sherva.voiceassistant.tts.TtsEngine(appContext)
        }
    }
    private val bargeInLazy: Lazy<BargeInDetector> = lazy {
        AppLog.i("VA", "初始化打断检测器...")
        BargeInDetector(appContext, threshold = config.bargeThreshold,
            startGuardMs = config.bargeGuardMs, minSpeechMs = config.bargeConfirmMs)
    }
    private val asr: StreamingAsrEngine get() = asrLazy.value
    private val kws: KeywordSpotterEngine get() = kwsLazy.value
    private val tts: TtsProvider get() = ttsLazy.value
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
    /** ★ 启动中标志：startConversation 到 LISTENING 状态之间防止重复触发。 */
    @Volatile private var starting = false
    /** 文字模式标志：文字模式发送后不自动重新聆听（保持静默等下一轮输入）。 */
    @Volatile var textMode = false
    private var historyInitialized = false

    @Volatile var state: State = State.IDLE
        private set

    @Synchronized
    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
        // 同时把“主 listener”指向最新加入者（兼容使用 listener.xxx 的旧代码）
        listener = l
        AppLog.i("VA", "addListener $l → 共 ${listeners.size} 个")
    }

    @Synchronized
    fun removeListener(l: Listener) {
        listeners.remove(l)
        if (listener === l) {
            listener = listeners.firstOrNull() ?: defaultListener
        }
        AppLog.i("VA", "removeListener $l → 剩 ${listeners.size} 个")
    }

    /** UI 线程广播给所有 listener。 */
    private fun broadcast(action: (Listener) -> Unit) {
        val snapshot = synchronized(this) { listeners.toList() }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            snapshot.forEach(action)
        } else {
            android.os.Handler(Looper.getMainLooper()).post {
                snapshot.forEach(action)
            }
        }
    }

    private fun setState(s: State) {
        state = s
        broadcast { it.onState(s) }
    }

    /** 初始化对话历史（system prompt）。语音/文字模式用不同提示词。 */
    private fun ensureHistory() {
        if (historyInitialized) return
        history.clear()
        // 根据 textMode 选对应系统提示词
        val prompt = if (textMode) config.systemPromptText else config.systemPrompt
        if (prompt.isNotBlank()) {
            history += LlmClient.Message.system(prompt)
        }
        historyInitialized = true
    }

    /** 启动对话循环（语音模式：开始聆听）。 */
    fun startConversation() {
        if (state != State.IDLE || starting) return  // ★ starting 防止异步加载期间重复触发
        starting = true
        ensureHistory()
        acquireWakeLock()
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
            starting = false  // ★ 已进入 LISTENING，允许下次重启
            active = false
            AppLog.i("VA", "模型就绪，开始聆听")
            // 模型就绪开始聆听的提示音
            com.sherva.voiceassistant.audio.SoundEffects.startListen()
            // ★ 等提示音播完再启动 ASR，避免提示音被麦克风录到干扰识别
            //   （开始聆听时没有 AEC，提示音回声会被 ASR 当语音处理）
            delay(300)
            asr.start(
                onPartial = { partial ->
                    lastPartialAt = System.currentTimeMillis()  // 唤醒监控：收到 partial 即重置
                    broadcast { it.onPartialText(partial) }
                },
                onFinal = { final -> onFinalText(final) },
            )
            // ★ 启动唤醒模式监控：wakeWordIdleSec 秒内无 partial → 进入 WAKE_WORD
            if (config.enableWakeWord) {
                startWakeWordWatchdog()
            }
        }
    }

    /** ★ 唤醒监控：ASR 启动后 wakeWordIdleSec 秒内无 partial 文本 → 进入 WAKE_WORD 模式。
     *  KWS 命中后立即重启 ASR（继续监听有效语音）。 */
    @Volatile private var lastPartialAt: Long = 0L
    @Volatile private var inWakeWord: Boolean = false

    private var wakeWordJob: Job? = null

    private fun startWakeWordWatchdog() {
        wakeWordJob?.cancel()
        lastPartialAt = System.currentTimeMillis()
        wakeWordJob = scope.launch {
            while (isActive && state == State.LISTENING && !inWakeWord) {
                delay(500)
                val idleMs = System.currentTimeMillis() - lastPartialAt
                val thresholdMs = (config.wakeWordIdleSec * 1000).toLong()
                if (idleMs >= thresholdMs && !textMode) {
                    AppLog.i("VA", "${config.wakeWordIdleSec}s 无有效语音，进入 WAKE_WORD 模式")
                    enterWakeWordMode()
                    break
                }
            }
        }
    }

    /** 进入 WAKE_WORD：停 ASR，启动 KWS。 */
    private fun enterWakeWordMode() {
        if (inWakeWord) return
        inWakeWord = true
        try { asr.stop() } catch (_: Throwable) {}
        setState(State.WAKE_WORD)
        AppLog.i("VA", "进入唤醒模式: 等待唤醒词 \"${config.wakeWord}\"")
        com.sherva.voiceassistant.audio.SoundEffects.wakeWord()
        try {
            kws.start { keyword ->
                AppLog.i("VA", "唤醒词命中: $keyword")
                inWakeWord = false
                exitWakeWordMode()
            }
        } catch (e: Throwable) {
            AppLog.e("VA", "KWS 启动失败", e)
            inWakeWord = false
            setState(State.IDLE)
        }
    }

    /** 唤醒命中：停 KWS，重启 ASR 继续监听。 */
    private fun exitWakeWordMode() {
        try { kws.stop() } catch (_: Throwable) {}
        // 回到 LISTENING：重启 ASR
        if (state != State.IDLE) {
            AppLog.i("VA", "唤醒后重启 ASR")
            com.sherva.voiceassistant.audio.SoundEffects.wakeHit()
            scope.launch {
                setState(State.LISTENING)
                active = false
                asr.start(
                    onPartial = { partial ->
                        lastPartialAt = System.currentTimeMillis()
                        broadcast { it.onPartialText(partial) }
                    },
                    onFinal = { final -> onFinalText(final) },
                )
                if (config.enableWakeWord) startWakeWordWatchdog()
            }
        }
    }

    /** 端点命中：拿到完整一句话 → 停 ASR → LLM → TTS。 */
    private fun onFinalText(text: String) {
        if (active) return   // 防重入
        active = true
        scope.launch {
            AppLog.i("VA", "收到 final: \"$text\"")
            asr.stop()
            broadcast { it.onUserText(text) }
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
            broadcast { it.onUserText(t) }
            history += LlmClient.Message.user(t)
            handleLlmTurn()
        }
    }

    /** LLM 流式 → TTS 播报（共用逻辑）。 */
    private suspend fun handleLlmTurn() {
        // LLM 流式（收集完整回复）
        setState(State.THINKING)
        // ★ STT/文字已发送给 pi 服务，开始思考的提示音（仅语音模式）
        if (!textMode) com.sherva.voiceassistant.audio.SoundEffects.sent()
        val reply = StringBuilder()
        val startTime = System.currentTimeMillis()
        var reasoningSeen = false
        try {
            llm.chat(history,
                onToken = { delta ->
                    reply.append(delta)
                    broadcast { it.onAssistantDelta(delta) }
                },
                onReasoning = {
                    if (!reasoningSeen) { reasoningSeen = true; broadcast { it.onReasoningStart() } }
                },
            ).collect { full ->
                if (full.length > reply.length) reply.clear().append(full)
            }
        } catch (e: Throwable) {
            broadcast { it.onError("LLM 调用失败: ${e.message}") }
        }
        val fullReply = reply.toString().trim()
        AppLog.i("VA", "LLM 完成: reasoning=$reasoningSeen, 回复 ${fullReply.length} 字: \"${fullReply.take(50)}\"")
        if (fullReply.isNotEmpty()) {
            broadcast { it.onAssistantComplete(fullReply) }
            history += LlmClient.Message.assistant(fullReply)

            if (textMode) {
                // 文字模式：不播报 TTS，只显示文字
                AppLog.i("VA", "文字模式，跳过 TTS 播报")
            } else {
                // TTS 播报：整段一次性喂 sherpa（内部按 token 分 batch 连续生成）
                //   不再外层按句分——避免每句 generate 的启动开销
                //   barge-in 通过 callback 返回 0 实现
                setState(State.SPEAKING)
                interrupted = false
                val cleaned = cleanTextForTts(fullReply)
                if (cleaned.isNotBlank()) {
                    speakAll(cleaned)
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
                // ★ 被打断时跳过冷却：用户已经开口说话，不应再等
                //   只有正常播完才冷却（防尾音回声）
                if (wasInterrupted) {
                    AppLog.i("VA", "打断后立即重新聆听（跳过冷却）")
                } else {
                    AppLog.i("VA", "冷却 ${config.cooldownMs}ms 后重新聆听")
                    delay(config.cooldownMs)
                }
                active = false   // 复位，准备下一轮
                startListening()
            }
        } else {
            active = false   // ★ 复位
            setState(State.IDLE)
        }
    }

    /**
     * 整段一次性喂 sherpa Kokoro（内部按 token 分 batch 连续生成）。
     * Barge-in 通过 callback 返回 0 实现。
     */
    private suspend fun speakAll(text: String) {
        if (text.isBlank()) return
        AppLog.i("VA", "TTS 整段播报：${text.length}字")
        // ★ 打断续程 cont：barge-in 时直接 resume，不等 sherpa native 跑完
        var speakCont: CancellableContinuation<Unit>? = null
        // ★ 打断支持：整个播报期间都开 Barge-in
        bargeIn.start(onInterrupt = {
            AppLog.i("VA", "BargeIn 触发回调，enableBargeIn=${config.enableBargeIn}")
            if (!config.enableBargeIn) return@start
            AppLog.i("VA", "打断触发 → 停 TTS，跳过剩余播报")
            com.sherva.voiceassistant.audio.SoundEffects.interrupt()
            interrupted = true
            tts.stop()
            llm.cancel()
            // ★ 立即跳出 speakAll 等待，不等 sherpa native batch 跑完
            speakCont?.takeIf { it.isActive }?.resume(Unit) { }
        })
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                speakCont = cont
                tts.speak(
                    text = text,
                    sid = config.ttsSid,
                    speed = config.ttsSpeed,
                    onComplete = { if (cont.isActive) cont.resume(Unit) { } },
                )
                cont.invokeOnCancellation { tts.stop() }
            }
        } finally {
            bargeIn.stop()
        }
    }

    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) {
            wakeLock.acquire(30 * 60 * 1000L)  // 30分钟超时兜底，防泄漏
            AppLog.i("VA", "WakeLock 已获取（息屏保活）")
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) {
            wakeLock.release()
            AppLog.i("VA", "WakeLock 已释放")
        }
    }

    /** ★ 切后台暂停：停侦听+TTS+BargeIn（保留会话状态，不释放引擎）。 */
    fun pause() {
        AppLog.i("VA", "切后台，暂停侦听与播放")
        interrupted = true
        if (bargeInLazy.isInitialized()) bargeIn.stop()
        if (asrLazy.isInitialized()) asr.stop()
        if (kwsLazy.isInitialized()) kws.stop()
        wakeWordJob?.cancel()
        inWakeWord = false
        if (ttsLazy.isInitialized()) tts.stop()
        // 注意：不取消 LLM（让进行中的请求自然完成或超时），不释放 wakeLock（保持可恢复）
        // 注意：不重置 state！保留 THINKING/SPEAKING 以便回前台后显示，
        //    仅仅隐藏 UI（setStartedUi(false) 会处理）
        // 如果原来是 LISTENING，状态可视为“后台停顿”。
        if (state == State.IDLE) {
            // 已经是空，不动作
        } else if (state == State.LISTENING) {
            // 后台停顿：保留 LISTENING 但停引擎
            AppLog.i("VA", "后台停顿（从 LISTENING）")
        } else {
            // THINKING/SPEAKING：保留状态，后台继续 LLM/TTS 请求
            AppLog.i("VA", "后台进行中（$state 保留）")
        }
    }

    /** ★ 回到前台恢复：仅当之前在 LISTENING（被 pause 后台停顿）才恢复聆听。
     *   IDLE = 待机状态，不自动唤醒（等用户手动点“开始对话”）。
     *   THINKING/SPEAKING = 保留进度，不打断。 */
    fun resume() {
        if (textMode) return  // 文字模式无需恢复聆听
        // 仅当之前是语音会话（引擎已初始化）才考虑恢复
        if (!asrLazy.isInitialized()) return
        // 如果正在 THINKING/SPEAKING，仅停 TTS 让用户回到前台后从对应进度继续
        if (state == State.THINKING || state == State.SPEAKING) {
            AppLog.i("VA", "回前台，进度继续 ($state)")
            return
        }
        // ★ WAKE_WORD 状态回前台：保持唤醒模式（KWS 继续跑）
        if (state == State.WAKE_WORD) {
            AppLog.i("VA", "回前台，继续唤醒模式（KWS 监听中）")
            return
        }
        // ★ 只有 LISTENING（pause 后台停顿）才恢复；IDLE 保持待机
        if (state == State.LISTENING) {
            AppLog.i("VA", "回前台，从后台停顿恢复聆听")
            active = false
            interrupted = false
            scope.launch { startListening() }
        } else {
            AppLog.i("VA", "回前台，当前 $state，不自动唤醒")
        }
    }

    fun stop() {
        convJob?.cancel()
        convJob = null
        active = false
        starting = false  // ★ 重置，允许重新开始
        interrupted = true
        wakeWordJob?.cancel()
        inWakeWord = false
        // 只停已初始化的引擎（避免 lazy 触发加载）
        if (bargeInLazy.isInitialized()) bargeIn.stop()
        if (asrLazy.isInitialized()) asr.stop()
        if (kwsLazy.isInitialized()) kws.stop()
        llm.cancel()
        if (ttsLazy.isInitialized()) tts.stop()
        releaseWakeLock()
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
        if (kwsLazy.isInitialized()) runCatching { kws.release() }
        if (ttsLazy.isInitialized()) runCatching { tts.release() }
        if (bargeInLazy.isInitialized()) runCatching { bargeIn.release() }
        releaseWakeLock()
    }

    // ---------- TTS 文本净化 ----------
    /** 净化文本用于 TTS（去所有标点/符号/markdown/emoji）。
     *
     * ★ 白名单法（比黑名单更彻底）：
     *   - 保留：字母（含中文，用 isLetter）、数字、空格
     *   - 保留句末标点（。，、！？,.!?）—— Kokoro 需要这些做停顿
     *   - 去掉：所有其他符号（箭头、括号、冒号、markdown、emoji 代理对等）
     *   - 不再逐个枚举 TTS_STRIP 列表 */
    private fun cleanTextForTts(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (Character.isSurrogate(ch)) continue   // 跳过 emoji
            if (ch == '\n') { sb.append('，'); continue }
            if (ch.isLetter() || ch.isDigit()) { sb.append(ch); continue }
            if (ch == ' ' || ch == '\t') { sb.append(ch); continue }
            if (ch in "。，、！？,.!?") { sb.append(ch); continue }
            // ★ 保留数字场景符号（供 digitsToChinese 正则匹配）
            //   含 - – 等范围连接符（如 20-21点）
            if (ch in ":.%°℃¥￥\$元点分-–") { sb.append(ch); continue }
            // 其他全去掉（括号、冒号、箭头、markdown 等一切标点符号）
        }
        return sb.toString()
            .replace(Regex("[ \t]+"), " ")
            .replace(Regex("[，。]{2,}"), "。")
            .trim()
    }
}
