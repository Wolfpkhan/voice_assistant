package com.sherva.voiceassistant

import android.Manifest
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
    }

    private lateinit var stateText: TextView
    private lateinit var partialText: TextView
    private lateinit var messagesView: RecyclerView
    private lateinit var startButton: MaterialButton
    private lateinit var settingsButton: MaterialButton
    private lateinit var historyButton: MaterialButton
    private lateinit var newChatButton: MaterialButton
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

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startAssistant() else toast("需要录音权限才能使用语音助手")
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
        scrollToEnd()
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
        // 当前是文字模式的 assistant（无语音会话）→ 释放并新建语音会话
        if (assistant != null && assistant?.textMode == true) {
            assistant?.release()
            assistant = null
            ensurePermissionAndStart()
        } else if (assistant != null) {
            stopAssistant()
        } else {
            ensurePermissionAndStart()
        }
    }

    /** 切换语音/文字模式（互斥：切走时停掉对方的会话）。 */
    private fun switchMode(newMode: Mode) {
        if (mode == newMode) return
        mode = newMode
        // 切走时若语音在跑，立即停止
        if (newMode == Mode.TEXT && assistant != null && assistant?.textMode == false) stopAssistant()
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

    private fun buildConfig(): VoiceAssistant.Config {
        val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        // 强制走 pi-proxy
        val cur = sp.getString(getString(R.string.pref_llm_baseurl), "") ?: ""
        if (cur.contains("8989")) {
            sp.edit().putString(getString(R.string.pref_llm_baseurl), "http://127.0.0.1:8988/v1").apply()
            AppLog.w("Main", "baseUrl 含 8989，已纠正为 8988(pi-proxy)")
        }
        val baseUrl = sp.getString(getString(R.string.pref_llm_baseurl), getString(R.string.default_baseurl))!!
        val apiKey = sp.getString(getString(R.string.pref_llm_apikey), getString(R.string.default_apikey))!!
        val model = sp.getString(getString(R.string.pref_llm_model), getString(R.string.default_model))!!
        AppLog.i("Main", "★ baseUrl=$baseUrl, model=$model")
        val system = sp.getString(getString(R.string.pref_llm_system), getString(R.string.default_system))!!
        val systemText = sp.getString(getString(R.string.pref_llm_system_text), getString(R.string.default_system_text))!!
        val speed = sp.getInt(getString(R.string.pref_tts_speed), 10) / 10.0f
        // ★ EditTextPreference 存的是 String，不能 getInt
        val ttsSid = sp.getString(getString(R.string.pref_tts_sid), "3")?.toIntOrNull() ?: 3
        val cooldownMs = sp.getInt(getString(R.string.pref_cooldown_ms), 600).toLong()
        val endpointSilence = sp.getInt(getString(R.string.pref_endpoint_silence), 12) / 10.0f
        val bargeGuardMs = sp.getInt(getString(R.string.pref_barge_guard_ms), 300).toLong()
        val bargeConfirmMs = sp.getInt(getString(R.string.pref_barge_confirm_ms), 200).toLong()
        val bargeThreshold = sp.getInt(getString(R.string.pref_barge_threshold), 6) / 10.0f
        val enableBargeIn = run {
            val spAec = getSharedPreferences("aec_probe", MODE_PRIVATE)
            val aecAvailable = spAec.getBoolean("available", false)
            // ★ AEC 可用才默认开启 bargeIn，否则关闭避免自打断
            //    用户可在设置中手动覆盖
            val userOverride = sp.getBoolean(getString(R.string.pref_enable_barge_in), false)
            userOverride || aecAvailable
        }
        // 麦克风增益 SeekBar 10..30 → 1.0..3.0
        val micGain = sp.getInt(getString(R.string.pref_mic_gain), 10) / 10.0f
        if (apiKey.isBlank()) toast("请先在「设置」里填写 API Key")
        return VoiceAssistant.Config(
            continuous = true, ttsSpeed = speed, ttsSid = ttsSid,
            llmBaseUrl = baseUrl, llmApiKey = apiKey, llmModel = model,
            systemPrompt = system, systemPromptText = systemText,
            cooldownMs = cooldownMs, endpointTrailingSilenceSec = endpointSilence,
            bargeGuardMs = bargeGuardMs, bargeConfirmMs = bargeConfirmMs, bargeThreshold = bargeThreshold,
            enableBargeIn = enableBargeIn,
            micGain = micGain,
        )
    }

    private fun startAssistant() {
        val cfg = buildConfig()
        if (cfg.llmApiKey.isBlank()) return
        assistant?.release()
        // ★ 不清空列表：保留已加载的历史对话，新对话继续追加
        curAssistantId = -1L
        assistant = VoiceAssistant(this, cfg, listener).also { it.startConversation() }
        setStartedUi(true)
    }

    private fun stopAssistant() {
        assistant?.stop()
        assistant = null
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
        // 回前台：恢复语音侦听（若有活跃语音会话）
        assistant?.resume()
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
        // 切后台：暂停侦听与播放（省电、防后台录音）
        assistant?.pause()
        // UI 临时复位（避免切回时看到误导状态；resume 后会按真实 state 恢复）
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
        if (assistant == null) {
            val cfg = buildConfig()
            if (cfg.llmApiKey.isBlank()) return
            assistant = VoiceAssistant(this, cfg, listener)
            // 不调 setStartedUi(true)：语音按钮状态只由语音会话管理
        }
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
        // 文字模式下不调 startAssistant（那会开启语音侦听），直接建实例发送
        if (assistant == null) {
            val cfg = buildConfig()
            if (cfg.llmApiKey.isBlank()) return
            assistant = VoiceAssistant(this, cfg, listener)
            // 不调 setStartedUi(true)：语音按钮状态只由语音会话管理
        }
        assistant?.textMode = true
        assistant?.sendText(text)
        // 发送后滚动到底（立即一次 + 延迟一次应对键盘收起后的布局变化）
        scrollToEnd()
        messagesView.postDelayed({ scrollToEnd() }, 200)
    }

    override fun onDestroy() {
        super.onDestroy()
        assistant?.release()
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
            }
            stateText.text = getString(label)
            stateText.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            // 停止生成按钮：思考中显示（文字/语音模式通用）
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
            val last = adapter.currentList.lastOrNull()
            // ★ 追加条件：最后一条是助手消息，且是本轮创建的（id 匹配）
            //   这样同一条流式回复始终追加同一气泡；新一轮（onUserText 已重置 id）则新建
            if (curAssistantId != -1L && last?.role == ChatMessage.Role.ASSISTANT && last.id == curAssistantId) {
                adapter.updateLastAssistant(last.text + delta)
            } else {
                val msg = ChatMessage.create(ChatMessage.Role.ASSISTANT, delta)
                curAssistantId = msg.id
                adapter.add(msg)
            }
            scrollToEnd()
        }

        override fun onAssistantComplete(text: String) = runOnUiThread {
            // ★ 以完整文本为准：无论增量是否走完，都用服务端完整回复覆盖/重建最后一条助手气泡。
            //   避免 pi 的流式 delta 拼接不完整导致气泡内容缺失。
            val final = text.trim()
            if (final.isEmpty()) return@runOnUiThread
            val last = adapter.currentList.lastOrNull()
            if (last?.role == ChatMessage.Role.ASSISTANT && last.id == curAssistantId) {
                adapter.updateLastAssistant(final)   // 覆盖文本，id 不变
            } else {
                val msg = ChatMessage.create(ChatMessage.Role.ASSISTANT, final)
                curAssistantId = msg.id
                adapter.add(msg)
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
