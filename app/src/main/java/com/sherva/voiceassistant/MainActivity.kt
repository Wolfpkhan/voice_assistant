package com.sherva.voiceassistant

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.sherva.voiceassistant.pipeline.VoiceAssistant
import com.sherva.voiceassistant.ui.ChatAdapter
import com.sherva.voiceassistant.ui.ChatMessage

class MainActivity : AppCompatActivity() {

    private lateinit var stateText: TextView
    private lateinit var partialText: TextView
    private lateinit var messagesView: RecyclerView
    private lateinit var startButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var settingsButton: MaterialButton
    private lateinit var interruptButton: MaterialButton
    private lateinit var muteButton: MaterialButton

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
            stopButton = findViewById(R.id.stopButton)
            settingsButton = findViewById(R.id.settingsButton)
            interruptButton = findViewById(R.id.interruptButton)
            muteButton = findViewById(R.id.muteButton)
            messagesView.layoutManager = LinearLayoutManager(this).apply {
                stackFromEnd = true
            }
            messagesView.adapter = adapter
            AppLog.i("Main", "View 绑定完成")
        } catch (t: Throwable) {
            AppLog.e("Main", "View 绑定失败", t); throw t
        }

        startButton.setOnClickListener { toggleConversation() }
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        interruptButton.setOnClickListener {
            assistant?.interruptOutput(); toast("已中断输出")
        }
        muteButton.setOnClickListener {
            assistant?.stopPlayback(); toast("已停止播放")
        }
    }

    /** 开始/停止 切换（ChatGPT 风格：单个主按钮 toggle）。 */
    private fun toggleConversation() {
        if (assistant != null) stopAssistant() else ensurePermissionAndStart()
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
        adapter.clearAll()
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
            startButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.warn)
        } else {
            // 待机：变“开始对话”样式（绿背景 + 麦克风）
            startButton.text = getString(R.string.btn_start)
            startButton.icon = ContextCompat.getDrawable(this, R.drawable.ic_mic)
            startButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.brand)
            stateText.text = getString(R.string.state_idle)
            stateText.backgroundTintList = ContextCompat.getColorStateList(this, R.color.state_idle)
        }
    }

    override fun onResume() {
        super.onResume()
        if (StoragePermission.granted()) AppLog.init(this)
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
                VoiceAssistant.State.IDLE -> R.string.state_idle to R.color.state_idle
                VoiceAssistant.State.LISTENING -> R.string.state_listening to R.color.state_listening
                VoiceAssistant.State.THINKING -> R.string.state_thinking to R.color.state_thinking
                VoiceAssistant.State.SPEAKING -> R.string.state_speaking to R.color.state_speaking
            }
            stateText.text = getString(label)
            stateText.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, color)
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
