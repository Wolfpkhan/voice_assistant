package com.sherva.voiceassistant

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.sherva.voiceassistant.pipeline.VoiceAssistant

class MainActivity : AppCompatActivity() {

    private lateinit var stateText: TextView
    private lateinit var conversationText: TextView
    private lateinit var youSaidText: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var startButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var settingsButton: MaterialButton

    private var assistant: VoiceAssistant? = null
    private val replyBuf = StringBuilder()
    private var assistantStreaming = false   // 是否正在增量输出助手回复

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startAssistant() else toast("需要录音权限才能使用语音助手")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 先初始化日志（filesDir 始终可写，不受权限影响）
        AppLog.init(this)
        AppLog.i("Main", "MainActivity.onCreate 开始")

        // 检查「所有文件访问权限」：用于把日志写到 Download 供用户/开发者查看
        if (!StoragePermission.granted()) {
            AppLog.w("Main", "未授予文件访问权限，弹出引导")
            showStoragePermissionDialog()
        }

        try {
            setContentView(R.layout.activity_main)
            AppLog.i("Main", "布局加载完成")
        } catch (t: Throwable) {
            AppLog.e("Main", "布局加载失败", t)
            throw t
        }

        try {
            stateText = findViewById(R.id.stateText)
            conversationText = findViewById(R.id.conversationText)
            youSaidText = findViewById(R.id.youSaidText)
            scrollView = findViewById(R.id.conversationScroll)
            startButton = findViewById(R.id.startButton)
            stopButton = findViewById(R.id.stopButton)
            settingsButton = findViewById(R.id.settingsButton)
            AppLog.i("Main", "View 绑定完成")
        } catch (t: Throwable) {
            AppLog.e("Main", "View 绑定失败", t)
            throw t
        }

        startButton.setOnClickListener { ensurePermissionAndStart() }
        stopButton.setOnClickListener { stopAssistant() }
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun showStoragePermissionDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("需要文件访问权限")
            .setMessage("为了把诊断日志（含闪退信息）写到 Download 目录便于排查，" +
                "请授予「所有文件访问权限」。\n\n" +
                "打开设置后，找到并开启本应用的权限，然后返回即可。")
            .setCancelable(false)
            .setPositiveButton("去授权") { _, _ ->
                StoragePermission.request(this)
            }
            .setNegativeButton("稍后") { _, _ ->
                toast("未授权时日志仅写入应用私有目录")
            }
            .show()
    }

    // 用户从权限设置页返回时重新检查
    override fun onResume() {
        super.onResume()
        if (StoragePermission.granted()) {
            AppLog.i("Main", "文件访问权限已授予")
            // 权限拿到后，重新初始化日志，使 Download 镜像生效
            AppLog.init(this)
        }
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
        // ★ 强制确保走 pi-proxy（8988）。历史上默认值改为 8988 后，部分安装仍残留 8989，
        //   这里主动纠正：若存的是 8989(llm-wire 直连)，覆盖为 8988(pi-proxy，带 agent 能力)。
        val cur = sp.getString(getString(R.string.pref_llm_baseurl), "") ?: ""
        if (cur.contains("8989")) {
            sp.edit().putString(getString(R.string.pref_llm_baseurl), "http://127.0.0.1:8988/v1").apply()
            AppLog.w("Main", "检测到 baseUrl=8989(llm-wire直连)，已强制纠正为 8988(pi-proxy)")
        }
        val baseUrl = sp.getString(getString(R.string.pref_llm_baseurl), getString(R.string.default_baseurl))!!
        val apiKey = sp.getString(getString(R.string.pref_llm_apikey), getString(R.string.default_apikey))!!
        val model = sp.getString(getString(R.string.pref_llm_model), getString(R.string.default_model))!!
        AppLog.i("Main", "★ 最终使用 baseUrl=$baseUrl, model=$model")
        val system = sp.getString(getString(R.string.pref_llm_system), getString(R.string.default_system))!!
        // SeekBar 5..20 → 0.5..2.0
        val speed = (sp.getInt(getString(R.string.pref_tts_speed), 10)) / 10.0f
        // 高级时间参数（SeekBar 原始整数 → 实际值）
        val cooldownMs = sp.getInt(getString(R.string.pref_cooldown_ms), 600).toLong()
        val endpointSilence = sp.getInt(getString(R.string.pref_endpoint_silence), 12) / 10.0f  // ×0.1s
        val bargeGuardMs = sp.getInt(getString(R.string.pref_barge_guard_ms), 300).toLong()
        val bargeConfirmMs = sp.getInt(getString(R.string.pref_barge_confirm_ms), 200).toLong()
        val bargeThreshold = sp.getInt(getString(R.string.pref_barge_threshold), 6) / 10.0f     // ×0.1
        if (apiKey.isBlank()) toast("请先在「设置」里填写 API Key")
        return VoiceAssistant.Config(
            continuous = true,
            ttsSpeed = speed,
            llmBaseUrl = baseUrl,
            llmApiKey = apiKey,
            llmModel = model,
            systemPrompt = system,
            cooldownMs = cooldownMs,
            endpointTrailingSilenceSec = endpointSilence,
            bargeGuardMs = bargeGuardMs,
            bargeConfirmMs = bargeConfirmMs,
            bargeThreshold = bargeThreshold,
        )
    }

    private fun startAssistant() {
        AppLog.i("Main", "startAssistant: 读取配置")
        val cfg = buildConfig()
        if (cfg.llmApiKey.isBlank()) { AppLog.w("Main", "apiKey 为空，中止"); return }
        AppLog.i("Main", "配置: baseUrl=${cfg.llmBaseUrl}, model=${cfg.llmModel}, speed=${cfg.ttsSpeed}")
        if (cfg.llmBaseUrl.contains("8989")) {
            AppLog.w("Main", "⚠ 当前连的是 llm-wire 直连(8989)，未启用 pi agent！")
            toast("当前未走 pi 服务，如需 agent 能力请改 baseUrl=8988")
        }
        assistant?.release()
        replyBuf.clear()
        conversationText.text = ""
        youSaidText.visibility = View.GONE
        AppLog.i("Main", "构造 VoiceAssistant ...")
        try {
            assistant = VoiceAssistant(this, cfg, listener).also {
                AppLog.i("Main", "VoiceAssistant 构造完成，启动对话")
                it.startConversation()
            }
            startButton.isEnabled = false
            stopButton.isEnabled = true
        } catch (t: Throwable) {
            AppLog.e("Main", "启动 VoiceAssistant 失败", t)
            toast("启动失败: ${t.message}")
        }
    }

    private fun stopAssistant() {
        assistant?.stop()
        assistant = null
        startButton.isEnabled = true
        stopButton.isEnabled = false
        stateText.text = getString(R.string.state_idle)
    }

    override fun onDestroy() {
        super.onDestroy()
        assistant?.release()
        assistant = null
    }

    // ---------- VoiceAssistant 回调（后台线程）→ UI ----------
    private val listener = object : VoiceAssistant.Listener {
        override fun onState(state: VoiceAssistant.State) = runOnUiThread {
            // 重新开始聆听时，清空上一轮的实时识别行
            if (state == VoiceAssistant.State.LISTENING) {
                youSaidText.visibility = View.GONE
                youSaidText.text = ""
            }
            stateText.text = when (state) {
                VoiceAssistant.State.IDLE -> getString(R.string.state_idle)
                VoiceAssistant.State.LISTENING -> getString(R.string.state_listening)
                VoiceAssistant.State.THINKING -> getString(R.string.state_thinking)
                VoiceAssistant.State.SPEAKING -> getString(R.string.state_speaking)
            }
            val bg = when (state) {
                VoiceAssistant.State.LISTENING -> 0xFF4CAF50.toInt()   // 绿
                VoiceAssistant.State.THINKING -> 0xFF2196F3.toInt()    // 蓝
                VoiceAssistant.State.SPEAKING -> 0xFF9C27B0.toInt()    // 紫
                VoiceAssistant.State.IDLE -> 0xFF3F51B5.toInt()        // 靛
            }
            stateText.setBackgroundColor(bg)
        }

        /** 流式实时识别：边说边出字、连续纠正，显示在「你说」行 */
        override fun onPartialText(text: String) = runOnUiThread {
            youSaidText.visibility = View.VISIBLE
            youSaidText.text = "你(实时)：$text"
        }

        override fun onUserText(text: String) = runOnUiThread {
            // 文本已确认并进入主对话区，清空实时识别行
            youSaidText.visibility = View.GONE
            youSaidText.text = ""
            replyBuf.append("\n\n你：").append(text)
            renderConversation()
        }

        override fun onAssistantDelta(delta: String) = runOnUiThread {
            // 流式增量：首次出现时先补「助手：」前缀，随后只追加 token 文本
            if (delta != "null" && delta.isNotBlank()) {
                if (!assistantStreaming) {
                    replyBuf.append("\n\n助手：")
                    assistantStreaming = true
                }
                replyBuf.append(delta)
                renderConversation()
            }
        }

        override fun onAssistantComplete(text: String) = runOnUiThread {
            // 只在“未收到任何增量”时补全（避免与 delta 重复）
            if (!assistantStreaming) {
                replyBuf.append("\n\n助手：").append(text)
                renderConversation()
            }
            assistantStreaming = false
        }

        override fun onError(message: String) = runOnUiThread {
            toast(message)
        }

        override fun onReasoningStart() = runOnUiThread {
            // DeepSeek-V4-Flash 先思考后作答；提示用户“正在想”，避免误以为卡死
            if (replyBuf.endsWith("助手：") || replyBuf.isEmpty()) {
                stateText.text = "深度思考中…"
            }
        }
    }

    private fun renderConversation() {
        conversationText.text = replyBuf.toString().trimStart()
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
