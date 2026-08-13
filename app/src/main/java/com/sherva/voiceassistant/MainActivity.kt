package com.sherva.voiceassistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.sherva.voiceassistant.data.ChatStore
import com.sherva.voiceassistant.data.MessageEntity
import com.sherva.voiceassistant.pipeline.VoiceAssistant
import com.sherva.voiceassistant.ui.ChatAdapter
import com.sherva.voiceassistant.ui.ChatMessage
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        /** 从历史/外部跳入时携带的文本，直接作为消息发送。 */
        const val EXTRA_TEXT_PROMPT = "extra_text_prompt"

        /** ★ 供 Service 复用配置（悬浮球模式下 Service 需要同样的 Config）。 */
        @JvmStatic
        fun buildServiceConfig(ctx: Context): VoiceAssistant.Config {
            val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)
            val cur = sp.getString(ctx.getString(R.string.pref_llm_baseurl), "") ?: ""
            if (cur.contains("8989")) {
                sp.edit().putString(ctx.getString(R.string.pref_llm_baseurl), "http://127.0.0.1:8988/v1").apply()
            }
            val baseUrl = sp.getString(ctx.getString(R.string.pref_llm_baseurl), ctx.getString(R.string.default_baseurl))!!
            val apiKey = sp.getString(ctx.getString(R.string.pref_llm_apikey), ctx.getString(R.string.default_apikey))!!
            val model = sp.getString(ctx.getString(R.string.pref_llm_model), ctx.getString(R.string.default_model))!!
            val system = sp.getString(ctx.getString(R.string.pref_llm_system), ctx.getString(R.string.default_system))!!
            val systemText = sp.getString(ctx.getString(R.string.pref_llm_system_text), ctx.getString(R.string.default_system_text))!!
            val speed = sp.getInt(ctx.getString(R.string.pref_tts_speed), 10) / 10.0f
            val ttsSid = sp.getInt(ctx.getString(R.string.pref_tts_sid), 3)
            val ttsEngine = sp.getString(ctx.getString(R.string.pref_tts_engine), "kokoro") ?: "kokoro"
            val cooldownMs = sp.getInt(ctx.getString(R.string.pref_cooldown_ms), 600).toLong()
            val endpointSilence = sp.getInt(ctx.getString(R.string.pref_endpoint_silence), 12) / 10.0f
            val bargeGuardMs = sp.getInt(ctx.getString(R.string.pref_barge_guard_ms), 300).toLong()
            val bargeConfirmMs = sp.getInt(ctx.getString(R.string.pref_barge_confirm_ms), 200).toLong()
            val bargeThreshold = sp.getInt(ctx.getString(R.string.pref_barge_threshold), 6) / 10.0f
            val enableBargeIn = run {
                val spAec = ctx.getSharedPreferences("aec_probe", Context.MODE_PRIVATE)
                val aecAvailable = spAec.getBoolean("available", false)
                sp.getBoolean(ctx.getString(R.string.pref_enable_barge_in), false) || aecAvailable
            }
            val micGain = sp.getInt(ctx.getString(R.string.pref_mic_gain), 10) / 10.0f
            val wakeWordIdleSec = sp.getInt(ctx.getString(R.string.pref_wake_word_idle_sec), 5).toFloat()
            val wakeWord = sp.getString(ctx.getString(R.string.pref_wake_word), "嗨赛琳娜") ?: "嗨赛琳娜"
            return VoiceAssistant.Config(
                continuous = true, ttsSpeed = speed, ttsSid = ttsSid,
                ttsEngine = ttsEngine,
                llmBaseUrl = baseUrl, llmApiKey = apiKey, llmModel = model,
                systemPrompt = system, systemPromptText = systemText,
                cooldownMs = cooldownMs, endpointTrailingSilenceSec = endpointSilence,
                bargeGuardMs = bargeGuardMs, bargeConfirmMs = bargeConfirmMs, bargeThreshold = bargeThreshold,
                enableBargeIn = enableBargeIn,
                micGain = micGain,
                enableWakeWord = true,
                wakeWordIdleSec = wakeWordIdleSec,
                wakeWord = wakeWord,
            )
        }
    }

    private lateinit var stateText: TextView
    private lateinit var partialText: TextView
    private lateinit var messagesView: RecyclerView
    private lateinit var startButton: MaterialButton
    private lateinit var settingsButton: MaterialButton
    private lateinit var historyButton: MaterialButton
    private lateinit var newChatButton: MaterialButton
    private lateinit var floatingBallButton: MaterialButton
    private lateinit var interruptButton: MaterialButton
    private lateinit var muteButton: MaterialButton
    private lateinit var textInput: TextInputEditText
    private lateinit var sendButton: android.widget.ImageButton
    private lateinit var stopGenButton: android.widget.ImageButton
    private lateinit var voiceModeButton: MaterialButton
    private lateinit var textModeButton: MaterialButton
    private lateinit var voiceBar: android.view.View
    private lateinit var textBar: android.view.View

    private enum class Mode { VOICE, TEXT }
    private var mode = Mode.VOICE   // 默认语音模式

    private val adapter = ChatAdapter()
    private var assistant: VoiceAssistant? = null
    @Volatile private var curAssistantId = -1L   // 当前流式助手消息 id（-1=未开始）

    /** ★ 流式输出节流：delta 累积到 buffer，每 100ms 刷一次 UI。 */
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pendingDelta = StringBuilder()
    private var flushRunnable: Runnable? = null
    private val FLUSH_INTERVAL_MS = 100L

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startAssistant() else toast("需要录音权限才能使用语音助手")
    }

    // ★ 悬浮球：权限请求 launcher
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (android.provider.Settings.canDrawOverlays(this)) {
            toast("悬浮窗权限已授予")
            enableFloatingBall()
        } else {
            toast("未授悬浮窗权限，无法显示悬浮球")
        }
    }
    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) toast("未授通知权限，后台服务可能被系统杀")
        enableFloatingBall()  // 即使没权限也启动
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        AppLog.i("Main", "MainActivity.onCreate 开始")

        if (!StoragePermission.granted()) {
            AppLog.w("Main", "未授予文件访问权限，弹出引导")
            showStoragePermissionDialog()
        }

        try {
            setContentView(R.layout.activity_main)
        } catch (t: Throwable) {
            AppLog.e("Main", "布局加载失败", t); throw t
        }

        try {
            stateText = findViewById(R.id.stateText)
            partialText = findViewById(R.id.partialText)
            messagesView = findViewById(R.id.messagesView)
            startButton = findViewById(R.id.startButton)
            settingsButton = findViewById(R.id.settingsButton)
            historyButton = findViewById(R.id.historyButton)
            newChatButton = findViewById(R.id.newChatButton)
            floatingBallButton = findViewById(R.id.floatingBallButton)
            interruptButton = findViewById(R.id.interruptButton)
            muteButton = findViewById(R.id.muteButton)
            textInput = findViewById(R.id.textInput)
            sendButton = findViewById(R.id.sendButton)
            stopGenButton = findViewById(R.id.stopGenButton)
            voiceModeButton = findViewById(R.id.voiceModeButton)
            textModeButton = findViewById(R.id.textModeButton)
            voiceBar = findViewById(R.id.voiceBar)
            textBar = findViewById(R.id.textBar)
            messagesView.layoutManager = LinearLayoutManager(this).apply {
                stackFromEnd = true
            }
            messagesView.adapter = adapter
            AppLog.i("Main", "View 绑定完成")
        } catch (t: Throwable) {
            AppLog.e("Main", "View 绑定失败", t); throw t
        }

        // 模式切换（互斥）
        voiceModeButton.setOnClickListener { switchMode(Mode.VOICE) }
        textModeButton.setOnClickListener { switchMode(Mode.TEXT) }
        applyMode()
        applyKeepScreenOn()

        // 聊天记录存储初始化 + 一次性加载历史（借鉴 hermes：getAllMessages 加载到内存，之后手动追加）
        ChatStore.initialize(this)
        com.sherva.voiceassistant.audio.SoundEffects.init(this)   // 音效初始化
        loadHistoryFromDb()

        startButton.setOnClickListener { toggleConversation() }
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        historyButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        // ★ 悬浮球开关：顶部按钮，默认关闭
        floatingBallButton.setOnClickListener { toggleFloatingBall() }
        // ★ 新对话：调 proxy /v1/new-session 开新 session（旧会话保留供 agent grep）
        newChatButton.setOnClickListener { startNewChat() }
        interruptButton.setOnClickListener {
            // ★ 同时中断 LLM 输出 + 停 TTS 播放（用户期望点'中断'就是全停）
            assistant?.interruptOutput()
            assistant?.stopPlayback()
            toast("已中断")
        }
        muteButton.setOnClickListener {
            assistant?.stopPlayback(); toast("已停止播放")
        }
        // 文字输入发送
        sendButton.setOnClickListener { sendTextFromInput() }
        // ★ 停止生成（文字/语音模式通用：停 LLM + 清除流式状态）
        stopGenButton.setOnClickListener {
            assistant?.interruptOutput()
            // 立即隐藏按钮 + 反馈，避免用户重复点击
            stopGenButton.visibility = android.view.View.GONE
            toast("已停止")
        }
        textInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN)
            ) {
                sendTextFromInput(); true
            } else false
        }
    }

    /** 开始/停止 切换（ChatGPT 风格：单个主按钮 toggle）。仅语音模式。 */
    /** 新对话：调 proxy 开新 session，并在列表插入提示（不弹框）。 */
    private fun startNewChat() {
        // 停掉当前会话，释放旧 assistant
        stopAssistant()
        // 列表插入提示消息（类似微信聊天列表分隔）
        adapter.add(ChatMessage.create(ChatMessage.Role.NOTICE, "以下为新对话"))
        // ★ 双重滚动到底部，确保显示新对话提示胶囊
        scrollToEnd(smooth = false)
        messagesView.postDelayed({ scrollToEnd(smooth = false) }, 200)
        // 后台调 proxy /v1/new-session（旧会话存盘，agent 可 grep）
        lifecycleScope.launch {
            runCatching {
                val sp = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
                val baseUrl = sp.getString(getString(R.string.pref_llm_baseurl), getString(R.string.default_baseurl))!!
                val url = baseUrl.trimEnd('/') + "/new-session"
                val client = okhttp3.OkHttpClient()
                val req = okhttp3.Request.Builder().url(url)
                    .header("Authorization", "Bearer " + sp.getString(getString(R.string.pref_llm_apikey), ""))
                    .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                    .build()
                client.newCall(req).execute().use { it.body?.string() }
            }
        }
    }

    private fun toggleConversation() {
        val a = assistant ?: run {
            // 无实例 → 建语音会话
            ensurePermissionAndStart()
            return
        }
        // ★ 用 state 判断是否在活跃语音会话（不能用 assistant==null，共享单例后永不为 null）
        val s = a.state
        if (s == com.sherva.voiceassistant.pipeline.VoiceAssistant.State.IDLE) {
            // 待机 → 切回语音模式（清除 textMode）+ 开始
            if (a.textMode) a.textMode = false
            ensurePermissionAndStart()
        } else {
            // 正在聆听/思考/播报 → 停止
            stopAssistant()
        }
    }

    /** ★ 悬浮球开关切换（顶部按钮）。 */
    private fun toggleFloatingBall() {
        val running = com.sherva.voiceassistant.service.VoiceAssistantService.instance != null
        if (running) {
            disableFloatingBall()
        } else {
            // 权限检查 → 悬浮窗 → 通知 → 启动
            if (!android.provider.Settings.canDrawOverlays(this)) {
                toast("需授权「显示在其他应用上层」才能显示悬浮球")
                overlayPermissionLauncher.launch(
                    android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        data = android.net.Uri.parse("package:${packageName}")
                    }
                )
                return
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    return
                }
            }
            enableFloatingBall()
        }
    }

    /** 启动悬浮球后台服务 + 按钮高亮。 */
    private fun enableFloatingBall() {
        val intent = android.content.Intent(this, com.sherva.voiceassistant.service.VoiceAssistantService::class.java)
            .setAction(com.sherva.voiceassistant.service.VoiceAssistantService.ACTION_START)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            startForegroundService(intent)
        else
            startService(intent)
        // 按钮高亮（品牌色）
        floatingBallButton.iconTint = android.content.res.ColorStateList.valueOf(0xFF10A37F.toInt())
        toast("悬浮球已开启")
        // ★ 延迟接管：Service 创建实例后，Activity 也注册 listener
        //   这样无论谁触发状态变化，App UI 和悬浮球都同步
        floatingBallButton.postDelayed({
            val shared = App.getAssistant(this)
            if (shared != null && assistant !== shared) {
                assistant = shared
                shared.addListener(listener)
                listener.onState(shared.state)
            }
        }, 500)
    }

    /** 关闭悬浮球后台服务 + 按钮复位。 */
    private fun disableFloatingBall() {
        val intent = android.content.Intent(this, com.sherva.voiceassistant.service.VoiceAssistantService::class.java)
            .setAction(com.sherva.voiceassistant.service.VoiceAssistantService.ACTION_STOP)
        startService(intent)
        // 按钮复位（灰色）
        floatingBallButton.iconTint = android.content.res.ColorStateList.valueOf(0xFF8E8E93.toInt())
        toast("悬浮球已关闭")
    }

    /** 同步悬浮球按钮状态（onResume 调用，确保图标高亮/灰色与实际服务状态一致）。 */
    private fun syncFloatingBallButton() {
        val running = com.sherva.voiceassistant.service.VoiceAssistantService.instance != null
        floatingBallButton.iconTint = if (running)
            android.content.res.ColorStateList.valueOf(0xFF10A37F.toInt())
        else
            android.content.res.ColorStateList.valueOf(0xFF8E8E93.toInt())
    }

    /** 切换语音/文字模式（互斥：切走时停掉对方的会话）。 */
    private fun switchMode(newMode: Mode) {
        if (mode == newMode) return
        mode = newMode
        // 切走时若语音在跑，立即停止
        if (newMode == Mode.TEXT && assistant != null && assistant?.textMode == false) stopAssistant()
        // ★ 切到文字模式：自动关闭悬浮球
        if (newMode == Mode.TEXT && com.sherva.voiceassistant.service.VoiceAssistantService.instance != null) {
            disableFloatingBall()
        }
        applyMode()
        // 切到语音模式：若当前无语音会话，按钮复位为“开始对话”
        if (newMode == Mode.VOICE && (assistant == null || assistant?.textMode == true)) {
            setStartedUi(false)
        }
    }

    /** 根据设置开关屏幕常亮（前台时 FLAG_KEEP_SCREEN_ON）。 */
    private fun applyKeepScreenOn() {
        val sp = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val keep = sp.getBoolean(getString(R.string.pref_keep_screen_on), false)
        if (keep) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** 应用当前模式的可见性。 */
    private fun applyMode() {
        val voice = mode == Mode.VOICE
        voiceBar.visibility = if (voice) android.view.View.VISIBLE else android.view.View.GONE
        textBar.visibility = if (voice) android.view.View.GONE else android.view.View.VISIBLE
        // 高亮当前模式按钮（用十六进制色值，避免依赖可能存在裁剪风险的 dark_* 资源）
        val activeTint = android.content.res.ColorStateList.valueOf(0xFF10A37F.toInt())       // 品牌绿
        val inactiveTint = android.content.res.ColorStateList.valueOf(0xFF383838.toInt())     // 深灰
        voiceModeButton.backgroundTintList = if (voice) activeTint else inactiveTint
        voiceModeButton.setTextColor(if (voice) android.graphics.Color.WHITE else 0xFF9B9BA7.toInt())
        textModeButton.backgroundTintList = if (!voice) activeTint else inactiveTint
        textModeButton.setTextColor(if (!voice) android.graphics.Color.WHITE else 0xFF9B9BA7.toInt())
        // 文字模式不显示实时识别提示
        if (!voice) partialText.visibility = android.view.View.GONE
    }

    private fun ensurePermissionAndStart() {
        if (hasRecordPermission()) startAssistant()
        else requestPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun hasRecordPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun buildConfig(): VoiceAssistant.Config = buildServiceConfig(this)

    private fun startAssistant() {
        val cfg = buildConfig()
        if (cfg.llmApiKey.isBlank()) return
        // ★ 复用 App.sharedAssistant：不释放（悬浮球可能接管）
        assistant?.let { existing ->
            // 已存在只订阅 + 启动（其他 listener 如 Service 仍保留）
            existing.addListener(listener)
            existing.startConversation()
            AppLog.i("Main", "复用 existing.startConversation()")
            setStartedUi(true)
            return
        }
        // 没有再创建
        val a = com.sherva.voiceassistant.pipeline.VoiceAssistant(this, cfg)
        a.addListener(listener)
        App.setAssistant(this, a)
        assistant = a
        a.startConversation()
        curAssistantId = -1L
        setStartedUi(true)
    }

    private fun stopAssistant() {
        assistant?.stop()
        // 不释放也不清 null，共享实例供 Service 接管
        setStartedUi(false)
    }

    private fun setStartedUi(started: Boolean) {
        interruptButton.isEnabled = started
        muteButton.isEnabled = started
        if (started) {
            // 对话中：变“停止”样式（深色背景 + 方块图标）
            startButton.text = getString(R.string.btn_stop)
            startButton.icon = ContextCompat.getDrawable(this, R.drawable.ic_stop)
            startButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE5484D.toInt())  // warn 红
        } else {
            // 待机：变“开始对话”样式（绿背景 + 麦克风）
            startButton.text = getString(R.string.btn_start)
            startButton.icon = ContextCompat.getDrawable(this, R.drawable.ic_mic)
            startButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF10A37F.toInt())  // 品牌绿
            stateText.text = getString(R.string.state_idle)
            stateText.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF6E6E80.toInt())  // 待机灰
        }
    }

    override fun onResume() {
        super.onResume()
        if (StoragePermission.granted()) AppLog.init(this)
        applyKeepScreenOn()  // 从设置页返回后重新应用
        syncFloatingBallButton()  // 同步悬浮球按钮高亮
        // ★ 接管 App.sharedAssistant（如果 Service 正在运行的话就是它）
        val shared = App.getAssistant(this)
        if (shared != null && shared !== assistant) {
            assistant = shared
            AppLog.i("Main", "onResume 接管 shared (state=${shared.state})")
        }
        // ★ 订阅回调：让 Activity 与 Service 同时收到状态变更（广播）
        assistant?.addListener(listener)
        // 按当前状态补发一次 onState（让 UI 立即对齐真实状态）
        assistant?.let { a ->
            listener.onState(a.state)
        }
        // 回前台：仅当没有 Service 在运行时才恢复语音侦听（避免和 Service 冲突）
        if (com.sherva.voiceassistant.service.VoiceAssistantService.instance == null) {
            assistant?.resume()
        } else {
            AppLog.i("Main", "onResume：Service 在运行，跳过 resume（让悬浮球继续管）")
        }
        // 按当前状态刷新 UI（避免状态丢失）: 文字模式不需要 startButton
        assistant?.let { a ->
            if (a.state == com.sherva.voiceassistant.pipeline.VoiceAssistant.State.IDLE) {
                setStartedUi(false)
            } else {
                setStartedUi(true)
            }
        }
        // 从历史页返回（可能清空/导入了历史）→ 重新加载
        loadHistoryFromDb()
        // 从历史页跳来：处理待发送文本
        handlePromptExtra()
    }

    override fun onPause() {
        super.onPause()
        // ★ 永远不移除 listener：LLM 文字流不中断，切后台也能继续更新聊天列表
        //   只暂停 TTS 播放和 STT 录音（pause 方法已处理）
        if (com.sherva.voiceassistant.service.VoiceAssistantService.instance != null) {
            AppLog.i("Main", "onPause：Service 在运行，跳过 pause（让悬浮球继续管）")
        } else {
            assistant?.pause()  // 只停 TTS/STT，不中断 LLM 文字流
        }
        setStartedUi(false)
    }

    /** 从数据库加载全部历史到列表（一次性构建，正序）。仅在没有活跃对话时刷新。 */
    private fun loadHistoryFromDb() {
        // 对话进行中不刷新（避免清掉正在流式显示的内容）
        if (assistant != null) return
        lifecycleScope.launch {
            val history = ChatStore.loadAll()
            // ★ 一次性构建整个列表再提交，避免 clearAll+逐个 add 的异步 DiffUtil 竞态叠加
            val msgs = history.map { m ->
                ChatMessage.create(
                    if (m.isFromUser) ChatMessage.Role.USER else ChatMessage.Role.ASSISTANT,
                    m.content,
                )
            }
            adapter.submitAll(msgs)
            scrollToEnd(smooth = false)
        }
    }

    /** 处理 EXTRA_TEXT_PROMPT（从历史页点击消息继续提问）。 */
    private fun handlePromptExtra() {
        val prompt = intent.getStringExtra(EXTRA_TEXT_PROMPT) ?: return
        intent.removeExtra(EXTRA_TEXT_PROMPT)
        // 文字模式发送：不开启语音侦听，不播报 TTS
        switchMode(Mode.TEXT)
        try {
            // 从 App 单例拿，如果不存在就新建并存入单例
            val a = App.getAssistant(this) ?: run {
                val cfg = buildConfig()
                if (cfg.llmApiKey.isBlank()) return
                val na = com.sherva.voiceassistant.pipeline.VoiceAssistant(this, cfg)
                App.setAssistant(this, na)
                na
            }
            a.addListener(listener)
            assistant = a
        } catch (_: Exception) { return }
        // 不调 setStartedUi(true)：语音按钮状态只由语音会话管理
        assistant?.textMode = true
        assistant?.sendText(prompt)
    }

    /** 文字输入发送（仅文字模式；不开启语音侦听，不播报 TTS）。 */
    private fun sendTextFromInput() {
        val text = textInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        // 文字模式：确保不在语音模式
        if (mode != Mode.TEXT) switchMode(Mode.TEXT)
        textInput.text?.clear()
        // 发送后折叠键盘
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(textInput.windowToken, 0)
        // 若语音会话还在跑，先停
        if (assistant != null && assistant?.textMode == false) stopAssistant()
        // 文字模式下不调 startAssistant（那会开启语音侦听），直接复用共享实例发送
        try {
            val a = App.getAssistant(this) ?: run {
                val cfg = buildConfig()
                if (cfg.llmApiKey.isBlank()) return
                val na = com.sherva.voiceassistant.pipeline.VoiceAssistant(this, cfg)
                App.setAssistant(this, na)
                na
            }
            a.addListener(listener)
            assistant = a
        } catch (_: Exception) { return }
        // 不调 setStartedUi(true)：语音按钮状态只由语音会话管理
        assistant?.textMode = true
        assistant?.sendText(text)
        // ★ 立即显示「停止生成」按钮：让用户在 pi 端 processing 时也能 abort
        //   按钮会在 LLM 响应完成（onAssistantComplete）或 cancel 后隐藏
        stopGenButton.visibility = android.view.View.VISIBLE
        // 发送后滚动到底（立即一次 + 延迟一次应对键盘收起后的布局变化）
        scrollToEnd()
        messagesView.postDelayed({ scrollToEnd() }, 200)
    }

    override fun onDestroy() {
        super.onDestroy()
        // ★ 不释放：Service 可能还在使用这个 VoiceAssistant 实例
        //   只取消订阅避免 Activity 回调泄露
        App.getAssistant(this)?.removeListener(listener)
        assistant = null
    }

    // ---------- VoiceAssistant 回调（后台线程）→ UI ----------
    private val listener = object : VoiceAssistant.Listener {
        override fun onState(state: VoiceAssistant.State) = runOnUiThread {
            val (label, color) = when (state) {
                VoiceAssistant.State.IDLE -> R.string.state_idle to 0xFF6E6E80.toInt()
                VoiceAssistant.State.LISTENING -> R.string.state_listening to 0xFF10A37F.toInt()
                VoiceAssistant.State.THINKING -> R.string.state_thinking to 0xFF5B8DEF.toInt()
                VoiceAssistant.State.SPEAKING -> R.string.state_speaking to 0xFFA855F7.toInt()
                VoiceAssistant.State.WAKE_WORD -> R.string.state_wake_word to 0xFFF59E0B.toInt()
            }
            stateText.text = getString(label)
            stateText.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            // ★ 按钮 UI 跟随状态（无论是 App 内点击还是悬浮球触发，都会同步）
            setStartedUi(state != VoiceAssistant.State.IDLE)
            // 停止生成按钮：THINKING 时显示（SPEAKING/IDLE 隐藏）
            stopGenButton.visibility = if (state == VoiceAssistant.State.THINKING) android.view.View.VISIBLE else android.view.View.GONE
            if (state != VoiceAssistant.State.LISTENING) {
                partialText.visibility = android.view.View.GONE
            }
        }

        override fun onPartialText(text: String) = runOnUiThread {
            partialText.visibility = android.view.View.VISIBLE
            partialText.text = "🗣 $text"
        }

        override fun onUserText(text: String) = runOnUiThread {
            partialText.visibility = android.view.View.GONE
            curAssistantId = -1L   // 新一轮，下一条助手消息会是新的
            adapter.add(ChatMessage.create(ChatMessage.Role.USER, text))
            ChatStore.save(text, isFromUser = true)   // 落库
            // ★ 双重滚动：立即 + 延迟（应对布局刷新延迟）
            scrollToEnd(smooth = false)
            messagesView.postDelayed({ scrollToEnd(smooth = false) }, 200)
        }

        override fun onAssistantDelta(delta: String) = runOnUiThread {
            if (delta == "null" || delta.isBlank()) return@runOnUiThread
            // ★ 节流：累积 delta，每 100ms 才刷一次 UI（Markdown 渲染很贵）
            synchronized(pendingDelta) {
                pendingDelta.append(delta)
                if (flushRunnable == null) {
                    flushRunnable = Runnable { flushDeltas() }
                    uiHandler.postDelayed(flushRunnable!!, FLUSH_INTERVAL_MS)
                }
            }
        }

        /** 取出累积的 delta 追加到最后一条助手气泡。 */
        private fun flushDeltas() {
            val batch: String
            synchronized(pendingDelta) {
                if (pendingDelta.isEmpty()) { flushRunnable = null; return }
                batch = pendingDelta.toString()
                pendingDelta.clear()
                flushRunnable = null
            }
            val last = adapter.currentList.lastOrNull()
            if (curAssistantId != -1L && last?.role == ChatMessage.Role.ASSISTANT && last.id == curAssistantId) {
                adapter.updateLastAssistant(last.text + batch)
            } else {
                val msg = ChatMessage.create(ChatMessage.Role.ASSISTANT, batch)
                curAssistantId = msg.id
                adapter.add(msg)
            }
            scrollToEnd()
        }

        override fun onAssistantComplete(text: String) = runOnUiThread {
            AppLog.i("Main", "onAssistantComplete: ${text.length} 字: \"${text.take(80)}\"")
            // ★ 先 flush 所有累积的 delta（确保流式片段不丢失）
            flushDeltas()
            // 以完整文本为准：覆盖或重建最后一条助手气泡，避免 delta 拼接不完整
            val final = text.trim()
            if (final.isEmpty()) return@runOnUiThread
            val last = adapter.currentList.lastOrNull()
            if (last?.role == ChatMessage.Role.ASSISTANT && last.id == curAssistantId) {
                adapter.updateLastAssistant(final)   // 覆盖文本，id 不变
                AppLog.i("Main", "覆盖最后一条助手气泡 (id=${curAssistantId})")
            } else {
                val msg = ChatMessage.create(ChatMessage.Role.ASSISTANT, final)
                curAssistantId = msg.id
                adapter.add(msg)
                AppLog.i("Main", "新增助手气泡 (id=${curAssistantId})")
            }
            ChatStore.save(final, isFromUser = false)   // 落库
            scrollToEnd()
            // 保持在"进行中"状态，供下一轮 onUserText 重置（避免与尾部 delta 竞态）
        }

        override fun onError(message: String) = runOnUiThread { toast(message) }

        override fun onReasoningStart() = runOnUiThread {
            if (curAssistantId == -1L) stateText.text = "深度思考中…"
        }
    }

    /** 滚动到底部。smooth=true 平滑（新消息到达），false 直接跳（首次加载）。借鉴 hermes 的 animateScrollToItem。 */
    private fun scrollToEnd(smooth: Boolean = true) {
        messagesView.post {
            val pos = adapter.itemCount - 1
            if (pos >= 0) {
                if (smooth) messagesView.smoothScrollToPosition(pos)
                else messagesView.scrollToPosition(pos)
            }
        }
    }

    private fun showStoragePermissionDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("需要文件访问权限")
            .setMessage("为了把诊断日志（含闪崩信息）写到 Download 目录便于排查，请授予「所有文件访问权限」。")
            .setCancelable(false)
            .setPositiveButton("去授权") { _, _ -> StoragePermission.request(this) }
            .setNegativeButton("稍后") { _, _ -> }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
