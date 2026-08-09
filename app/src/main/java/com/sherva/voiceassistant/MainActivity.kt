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
    private lateinit var interruptButton: MaterialButton
    private lateinit var muteButton: MaterialButton
    private lateinit var textInput: TextInputEditText
    private lateinit var sendButton: MaterialButton
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
            interruptButton = findViewById(R.id.interruptButton)
            muteButton = findViewById(R.id.muteButton)
            textInput = findViewById(R.id.textInput)
            sendButton = findViewById(R.id.sendButton)
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

        // 聊天记录存储初始化 + 加载历史
        ChatStore.initialize(this)
        lifecycleScope.launch {
            val history = ChatStore.loadAll()
            history.forEach { m ->
                adapter.add(ChatMessage.create(
                    if (m.isFromUser) ChatMessage.Role.USER else ChatMessage.Role.ASSISTANT,
                    m.content,
                ))
            }
            scrollToEnd()
        }

        startButton.setOnClickListener { toggleConversation() }
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        historyButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        interruptButton.setOnClickListener {
            assistant?.interruptOutput(); toast("已中断输出")
        }
        muteButton.setOnClickListener {
            assistant?.stopPlayback(); toast("已停止播放")
        }
        // 文字输入发送
        sendButton.setOnClickListener { sendTextFromInput() }
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
    private fun toggleConversation() {
        if (assistant != null) stopAssistant() else ensurePermissionAndStart()
    }

    /** 切换语音/文字模式（互斥：切走时停掉对方的会话）。 */
    private fun switchMode(newMode: Mode) {
        if (mode == newMode) return
        mode = newMode
        // 切走时若语音在跑，立即停止
        if (newMode == Mode.TEXT && assistant != null) stopAssistant()
        applyMode()
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
        val speed = sp.getInt(getString(R.string.pref_tts_speed), 10) / 10.0f
        val cooldownMs = sp.getInt(getString(R.string.pref_cooldown_ms), 600).toLong()
        val endpointSilence = sp.getInt(getString(R.string.pref_endpoint_silence), 12) / 10.0f
        val bargeGuardMs = sp.getInt(getString(R.string.pref_barge_guard_ms), 300).toLong()
        val bargeConfirmMs = sp.getInt(getString(R.string.pref_barge_confirm_ms), 200).toLong()
        val bargeThreshold = sp.getInt(getString(R.string.pref_barge_threshold), 6) / 10.0f
        if (apiKey.isBlank()) toast("请先在「设置」里填写 API Key")
        return VoiceAssistant.Config(
            continuous = true, ttsSpeed = speed,
            llmBaseUrl = baseUrl, llmApiKey = apiKey, llmModel = model, systemPrompt = system,
            cooldownMs = cooldownMs, endpointTrailingSilenceSec = endpointSilence,
            bargeGuardMs = bargeGuardMs, bargeConfirmMs = bargeConfirmMs, bargeThreshold = bargeThreshold,
        )
    }

    private fun startAssistant() {
        val cfg = buildConfig()
        if (cfg.llmApiKey.isBlank()) return
        assistant?.release()
        // 语音模式每次开始是新一轮（清空列表）；文字模式保留历史
        if (mode == Mode.VOICE) {
            adapter.clearAll()
            curAssistantId = -1L
        }
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
        // 从历史页跳来：处理待发送文本
        handlePromptExtra()
    }

    /** 处理 EXTRA_TEXT_PROMPT（从历史页点击消息继续提问）。 */
    private fun handlePromptExtra() {
        val prompt = intent.getStringExtra(EXTRA_TEXT_PROMPT) ?: return
        intent.removeExtra(EXTRA_TEXT_PROMPT)
        // 自动开始对话并发送文本
        if (assistant == null) {
            if (hasRecordPermission()) startAssistant() else ensurePermissionAndStart()
        }
        assistant?.sendText(prompt)
    }

    /** 文字输入发送（仅文字模式；语音在跑则先停，保证互斥）。 */
    private fun sendTextFromInput() {
        val text = textInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        // 文字模式：确保不在语音模式
        if (mode != Mode.TEXT) switchMode(Mode.TEXT)
        // 若语音会话还在跑（如刚切过来残留），先停
        if (assistant != null) stopAssistant()
        textInput.text?.clear()
        // 初始化并发送
        if (assistant == null) {
            if (!hasRecordPermission()) {
                toast("文字模式无需录音，但需要初始化")
            }
        }
        startAssistant()
        assistant?.sendText(text)
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
            stateText.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, color))
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
            scrollToEnd()
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

    private fun scrollToEnd() {
        messagesView.post {
            val pos = adapter.itemCount - 1
            if (pos >= 0) messagesView.scrollToPosition(pos)
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
