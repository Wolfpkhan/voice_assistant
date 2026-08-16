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
            val baseUrl = sp.getString(ctx.getString(R.string.pref_llm_baseurl), ctx.getString(R.string.default_baseurl))!!
            val apiKey = sp.getString(ctx.getString(R.string.pref_llm_apikey), "") ?: ""
            val model = sp.getString(ctx.getString(R.string.pref_llm_model), ctx.getString(R.string.default_model))!!
            val system = sp.getString(ctx.getString(R.string.pref_llm_system), ctx.getString(R.string.default_system))!!
            val systemText = sp.getString(ctx.getString(R.string.pref_llm_system_text), ctx.getString(R.string.default_system_text))!!
            val speed = sp.getInt(ctx.getString(R.string.pref_tts_speed), 10) / 10.0f
            val ttsSid = sp.getInt(ctx.getString(R.string.pref_tts_sid), 3)
            val ttsEngine = sp.getString(ctx.getString(R.string.pref_tts_engine), "kokoro") ?: "kokoro"
            val cooldownMs = sp.getInt(ctx.getString(R.string.pref_cooldown_ms), 600).toLong()
            val endpointSilence = sp.getInt(ctx.getString(R.string.pref_endpoint_silence), 12) / 10.0f
            val micGain = sp.getInt(ctx.getString(R.string.pref_mic_gain), 10) / 10.0f
            val wakeWordIdleSec = sp.getInt(ctx.getString(R.string.pref_wake_word_idle_sec), 5).toFloat()
            val kwsConfirmWindowSec = sp.getInt(ctx.getString(R.string.pref_kws_confirm_window_sec), 5).toFloat()
            val wakeGraceSec = sp.getInt(ctx.getString(R.string.pref_wake_grace_sec), 8).toFloat()
            val wakeWord = sp.getString(ctx.getString(R.string.pref_wake_word), ctx.getString(R.string.default_wake_word)) ?: ctx.getString(R.string.default_wake_word)
            val globalAec = sp.getBoolean(ctx.getString(R.string.pref_global_aec), false)
            val kwsNoiseSuppressor = sp.getBoolean(ctx.getString(R.string.pref_kws_ns), true)
            val kwsGtcrn = sp.getBoolean(ctx.getString(R.string.pref_kws_gtcrn), false)
            val kwsSco = sp.getBoolean(ctx.getString(R.string.pref_kws_sco), false)
            val pauseMusic = sp.getBoolean(ctx.getString(R.string.pref_pause_music), false)
            return VoiceAssistant.Config(
                continuous = true, ttsSpeed = speed, ttsSid = ttsSid,
                ttsEngine = ttsEngine,
                llmBaseUrl = baseUrl, llmApiKey = apiKey, llmModel = model,
                systemPrompt = system, systemPromptText = systemText,
                cooldownMs = cooldownMs, endpointTrailingSilenceSec = endpointSilence,
                micGain = micGain,
                enableWakeWord = true,
                wakeWordIdleSec = wakeWordIdleSec,
                kwsConfirmWindowSec = kwsConfirmWindowSec,
                wakeGraceSec = wakeGraceSec,
                wakeWord = wakeWord,
                globalAec = globalAec,
                kwsNoiseSuppressor = kwsNoiseSuppressor,
                kwsGtcrn = kwsGtcrn,
                kwsBluetoothSco = kwsSco,
                pauseMusic = pauseMusic,
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
    private lateinit var undoButton: MaterialButton
    private lateinit var muteButton: MaterialButton
    private lateinit var textInput: TextInputEditText
    private lateinit var sendButton: android.widget.ImageButton
    private lateinit var stopGenButton: android.widget.ImageButton
    private lateinit var attachButton: android.widget.ImageButton
    private lateinit var voiceModeButton: MaterialButton
    private lateinit var textModeButton: MaterialButton
    private lateinit var voiceBar: android.view.View
    private lateinit var textBar: android.view.View

    // ★ 历史分页：启动只加载最近 PAGE_SIZE 条，滚到顶时加载更早的
    private var loadingMore = false   // 防止重复触发
    private var hasMoreHistory = false // 数据库里是否还有更早的消息未加载

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
    /** ★ reasoning 节流间隔（比 text 慢，避免高频重绘眼花）。 */
    private val REASONING_FLUSH_INTERVAL_MS = 300L
    /** ★ 当前助手气泡已写入 UI 的文本同步追踪（避免 AsyncListDiffer 异步 currentList 带来的竞态）。 */
    private val streamedText = StringBuilder()
    /** ★ 当前助手气泡已写入 UI 的 reasoning 同步追踪。 */
    private val streamedReasoning = StringBuilder()

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startAssistant() else toast(getString(R.string.toast_need_mic_permission))
    }

    // ★ 悬浮球：权限请求 launcher
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (android.provider.Settings.canDrawOverlays(this)) {
            toast(getString(R.string.toast_overlay_granted))
            enableFloatingBall()
        } else {
            toast(getString(R.string.toast_overlay_denied))
        }
    }
    // ★ 附件选择 launcher：OpenMultipleDocuments 拿到多个 Uri，需用 ContentResolver 取真实路径
    private val pickFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val paths = uris.mapNotNull { uri ->
            val p = getPathFromUri(uri)
            AppLog.i("AttachPicker", "uri=$uri -> path=$p")
            p
        }
        if (paths.isEmpty()) {
            toast(getString(R.string.toast_no_path))
            return@registerForActivityResult
        }
        // ★ 多个路径以换行符拼接（不入换行符的为首则不加；已有文本后追加换行）
        val cur = textInput.text?.toString().orEmpty()
        val separator = if (cur.isEmpty() || cur.endsWith("\n")) "" else "\n"
        textInput.setText(cur + separator + paths.joinToString("\n"))
        textInput.setSelection(textInput.text?.length ?: 0)
    }

    /**
     * 把 Uri 转成可读的文件路径（偏好全路径，如 /sdcard/Download/xxx.mp3）：
     *  1. file:// scheme → 直接 path
     *  2. ContentResolver 查 _data/_DATA/data 列（Android ≤10  MediaStore可用）
     *  3. DocumentsContract.getDocumentId 解析 primary:Download/xxx / msd:12345 / raw:...
     *  4. MediaStore.Files 查 _id 拿完整 _data
     *  5. 仅拿到文件名 → /sdcard/Download 等常见路径猜
     * 返回 null 表示完全失败（打 toast）。
     */
    private fun getPathFromUri(uri: android.net.Uri): String? {
        val cr = contentResolver
        // 1. file:// scheme
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            val p = uri.path
            if (!p.isNullOrEmpty() && java.io.File(p).exists()) return p
            return p
        }

        // 2. ContentResolver 查 _data / DATA 字段
        runCatching {
            cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, "_data", "_DATA", "data", "_id"), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val pathIdx = listOf("_data", "_DATA", "data").firstNotNullOfOrNull { idx ->
                        val i = c.getColumnIndex(idx)
                        if (i >= 0 && !c.isNull(i)) c.getString(i) else null
                    }
                    if (!pathIdx.isNullOrEmpty()) return pathIdx
                    val displayName = if (nameIdx >= 0) c.getString(nameIdx) else null

                    // 3. DocumentsContract.getDocumentId 解析 primary:Download/xxx / tree...
                    if (android.provider.DocumentsContract.isDocumentUri(this, uri)) {
                        val docId = android.provider.DocumentsContract.getDocumentId(uri)
                        AppLog.i("AttachPicker", "docId=$docId")
                        // primary:Download/xxx.mp3 → /storage/emulated/0/Download/xxx.mp3
                        if (docId.startsWith("primary:")) {
                            val rel = docId.removePrefix("primary:").trimStart('/')
                            val full = "/storage/emulated/0/$rel"
                            if (java.io.File(full).exists()) return full
                            val alt = "/sdcard/$rel"
                            if (java.io.File(alt).exists()) return alt
                            return full
                        }
                        // raw:/storage/... 直接用
                        if (docId.startsWith("raw:")) {
                            return docId.removePrefix("raw:")
                        }
                        // ★ image:xxx / video:xxx / audio:xxx / msd:xxx / msf:xxx → MediaStore 查 _data
                        val colonIdx = docId.indexOf(':')
                        if (colonIdx > 0) {
                            val type = docId.substring(0, colonIdx)
                            val id = docId.substring(colonIdx + 1).toLongOrNull()
                            if (id != null) {
                                val mediaPath = queryMediaStorePathByType(type, id)
                                if (!mediaPath.isNullOrEmpty()) return mediaPath
                            }
                        }
                    }

                    // 4. ★ Uri 本身就是 MediaStore Uri（com.android.providers.media.documents 不走 DocumentsContract 的意外场景）
                    val directMediaId = uri.lastPathSegment?.toLongOrNull()
                    if (directMediaId != null && uri.authority != null) {
                        val mediaPath = queryMediaStorePath(uri.authority!!, directMediaId)
                        if (!mediaPath.isNullOrEmpty()) return mediaPath
                    }

                    // 5. 只拿到文件名，在常见路径拼
                    if (!displayName.isNullOrEmpty()) {
                        listOf("/sdcard/Download/", "/sdcard/Documents/", "/storage/emulated/0/Download/", "/storage/emulated/0/Documents/", "/sdcard/Music/", "/sdcard/Movies/").forEach { dir ->
                            val candidate = "$dir$displayName"
                            if (java.io.File(candidate).exists()) return candidate
                        }
                        return "/sdcard/Download/$displayName"
                    }
                }
            }
        }
        return null
    }

    /**
     * MediaStore.Images / Media / Files 表里查 _id 对应 _data。
     * authority 通常是 "media"（外采 provider 也可能同名）。
     */
    private fun queryMediaStorePath(authority: String, id: Long): String? {
        val projection = arrayOf(android.provider.MediaStore.MediaColumns.DATA, "_data")
        val selection = "_id=?"
        val args = arrayOf(id.toString())
        listOf(android.provider.MediaStore.Files.getContentUri("external"), android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI).forEach { base ->
            runCatching {
                contentResolver.query(base, projection, selection, args, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val dataIdx = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                        if (dataIdx >= 0 && !c.isNull(dataIdx)) {
                            val p = c.getString(dataIdx)
                            if (!p.isNullOrEmpty()) return p
                        }
                        val dataIdx2 = c.getColumnIndex("_data")
                        if (dataIdx2 >= 0 && !c.isNull(dataIdx2)) {
                            return c.getString(dataIdx2)
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * 根据 docId 前缀 (image/video/audio/msd/msf) 查对应 MediaStore 表的 _data。
     * vivo Android 16 选了微信图片后 docId="image:1000169075"，此函数拿真实路径。
     */
    private fun queryMediaStorePathByType(type: String, id: Long): String? {
        val base: android.net.Uri = when (type.lowercase()) {
            "image" -> android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "video" -> android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "audio" -> android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            "msd", "msf", "document" -> android.provider.MediaStore.Files.getContentUri("external")
            else -> return null
        }
        val projection = arrayOf(android.provider.MediaStore.MediaColumns.DATA, "_data")
        val selection = "_id=?"
        val args = arrayOf(id.toString())
        AppLog.i("AttachPicker", "queryMediaStorePathByType type=$type id=$id base=$base")
        return runCatching {
            contentResolver.query(base, projection, selection, args, null)?.use { c ->
                if (c.moveToFirst()) {
                    val dataIdx = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                    if (dataIdx >= 0 && !c.isNull(dataIdx)) {
                        val p = c.getString(dataIdx)
                        AppLog.i("AttachPicker", "got DATA=$p")
                        return@use p
                    }
                    val dataIdx2 = c.getColumnIndex("_data")
                    if (dataIdx2 >= 0 && !c.isNull(dataIdx2)) {
                        val p = c.getString(dataIdx2)
                        AppLog.i("AttachPicker", "got _data=$p")
                        return@use p
                    }
                    AppLog.w("AttachPicker", "row found but no _data column (c.count=${c.columnCount})")
                } else {
                    AppLog.w("AttachPicker", "no row for $type id=$id")
                }
                null
            }
        }.onFailure { AppLog.e("AttachPicker", "queryMediaStorePathByType failed", it) }.getOrNull()
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) toast(getString(R.string.toast_need_notif))
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
            undoButton = findViewById(R.id.undoButton)
            muteButton = findViewById(R.id.muteButton)
            textInput = findViewById(R.id.textInput)
            sendButton = findViewById(R.id.sendButton)
            stopGenButton = findViewById(R.id.stopGenButton)
            attachButton = findViewById(R.id.attachButton)
            voiceModeButton = findViewById(R.id.voiceModeButton)
            textModeButton = findViewById(R.id.textModeButton)
            voiceBar = findViewById(R.id.voiceBar)
            textBar = findViewById(R.id.textBar)
            messagesView.layoutManager = LinearLayoutManager(this).apply {
                stackFromEnd = true
            }
            // ★ 关闭 change 动画：流式内容高度频繁变化时，change 预测动画导致锚点跳动
            //   （思考折叠/正文增长 → item 高度骤变 → 锚点补偿 → 上下滚动）
            messagesView.itemAnimator = null
            messagesView.adapter = adapter
            // ★ 滚到顶时加载更早的历史（分页）
            messagesView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val lm = rv.layoutManager as? LinearLayoutManager ?: return
                    // dy<0 = 向上滑；到顶（第一个可见 item 是 0）时触发
                    if (dy < 0 && lm.findFirstVisibleItemPosition() <= 2) {
                        loadMoreHistory()
                    }
                }
            })
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
            toast(getString(R.string.toast_interrupted))
        }
        // ★ 撤销当前 STT 内容并重新聆听（仅 LISTENING 状态可用）
        undoButton.setOnClickListener {
            assistant?.discardAndRelisten()
            partialText.text = ""
            partialText.visibility = android.view.View.GONE
            toast(getString(R.string.toast_revoked))
        }
        muteButton.setOnClickListener {
            assistant?.stopPlayback(); toast(getString(R.string.toast_playback_stopped))
        }
        // 文字输入发送
        sendButton.setOnClickListener { sendTextFromInput() }
        attachButton.setOnClickListener {
            // ★ 弹出系统文件选择器（多选），仅接受常规文件路径
            try {
                pickFilesLauncher.launch(arrayOf("*/*"))
            } catch (e: android.content.ActivityNotFoundException) {
                toast(getString(R.string.toast_no_file_picker))
            }
        }
        // ★ 停止生成（文字/语音模式通用：停 LLM + 清除流式状态）
        stopGenButton.setOnClickListener {
            assistant?.interruptOutput()
            // 立即隐藏按钮 + 反馈，避免用户重复点击
            stopGenButton.visibility = android.view.View.GONE
            toast(getString(R.string.toast_stopped))
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
        adapter.add(ChatMessage.create(ChatMessage.Role.NOTICE, getString(R.string.notice_new_chat)))
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
                toast(getString(R.string.toast_need_overlay))
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

    /** 启动悬浮球后台服务 + 按钮高亮。silent=true 时不弹 toast（自动开启场景）。 */
    private fun enableFloatingBall(silent: Boolean = false) {
        val intent = android.content.Intent(this, com.sherva.voiceassistant.service.VoiceAssistantService::class.java)
            .setAction(com.sherva.voiceassistant.service.VoiceAssistantService.ACTION_START)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            startForegroundService(intent)
        else
            startService(intent)
        // 按钮高亮（品牌色）
        floatingBallButton.iconTint = android.content.res.ColorStateList.valueOf(0xFF10A37F.toInt())
        if (!silent) toast(getString(R.string.toast_ball_on))
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
        toast(getString(R.string.toast_ball_off))
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
        // ★ 启动语音会话时自动开启悬浮球（权限齐备才开，静默）——
        //   开始对话了才需要后台保活；切模式不触发
        if (com.sherva.voiceassistant.service.VoiceAssistantService.instance == null) {
            val overlayOk = android.provider.Settings.canDrawOverlays(this)
            val notifOk = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (overlayOk && notifOk) enableFloatingBall(silent = true)
        }
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
        // ★ 回前台：恢复语音侦听 / 唤醒词监听
        //   即使 Service 在跑（悬浮球模式）也要调 resume，因为切后台时 KWS 被 pause 停了，
        //   需要在这里重启 KWS（resume 内部判断 state 决定动作）
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
        // ★ 接收其他 App 分享的文本/文件
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // ★ 分享 Intent：App 已在运行时从其他 App 分享进来走这里（singleTop）
        handleShareIntent(intent)
        handlePromptExtra()
    }

    /** ★ 处理 ACTION_SEND / ACTION_SEND_MULTIPLE：把分享的文本/文件路径填入文字模式输入框。
     *  不直接发送，用户可编辑后点发送（参考 hermes_chat_android）。 */
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        if (action != android.content.Intent.ACTION_SEND &&
            action != android.content.Intent.ACTION_SEND_MULTIPLE) return

        // 切到文字模式（分享默认走文字模式）
        if (mode != Mode.TEXT) switchMode(Mode.TEXT)

        // ★ 解析分享内容为文本/路径列表
        val parts = mutableListOf<String>()
        when (action) {
            android.content.Intent.ACTION_SEND -> {
                // 1. 纯文本
                val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) {
                    parts.add(text.trim())
                }
                // 2. 文件 URI（EXTRA_STREAM 或 ClipData）
                val streamUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
                }
                if (streamUri != null) {
                    val p = getPathFromUri(streamUri) ?: streamUri.toString()
                    if (parts.isEmpty() || parts.last() != p) parts.add(p)
                } else if (parts.isEmpty()) {
                    intent.clipData?.getItemAt(0)?.uri?.let { uri ->
                        parts.add(getPathFromUri(uri) ?: uri.toString())
                    }
                }
            }
            android.content.Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
                }
                uris?.forEach { uri ->
                    parts.add(getPathFromUri(uri) ?: uri.toString())
                }
            }
        }
        // ★ 清空 Intent（防止 Activity 重建时重复触发）
        //   只改 action，不能 setIntent(null)，否则后续 onResume 里 getIntent() 返回 null
        //   导致 handlePromptExtra 读 getStringExtra → NPE 崩溃
        intent.action = null
        intent.removeCategory(android.content.Intent.CATEGORY_DEFAULT)
        if (parts.isEmpty()) {
            AppLog.i("Main", "分享 Intent：未解析到内容")
            return
        }
        // ★ 填入输入框（多个用换行分隔，与文件选择按钮一致）
        AppLog.i("Main", "分享 Intent：填入 ${parts.size} 项")
        val cur = textInput.text?.toString().orEmpty()
        val separator = if (cur.isEmpty() || cur.endsWith("\n")) "" else "\n"
        textInput.setText(cur + separator + parts.joinToString("\n"))
        textInput.setSelection(textInput.text?.length ?: 0)
        // 聚焦输入框、弹键盘
        textInput.requestFocus()
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(textInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
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

    /** 从数据库加载最近一页历史到列表。仅在没有活跃对话时刷新。 */
    private fun loadHistoryFromDb() {
        // 对话进行中不刷新（避免清掉正在流式显示的内容）
        if (assistant != null) return
        lifecycleScope.launch {
            val history = ChatStore.loadLatest()   // ★ 只加载最近 PAGE_SIZE 条
            val total = ChatStore.count()
            hasMoreHistory = total > history.size   // 数据库还有更早的消息
            AppLog.i("Main", "历史加载：已加载 ${history.size} 条，数据库共 $total 条，分页加载=${hasMoreHistory}")
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

    /** ★ 滚到顶时加载更早的历史（分页）。 */
    private fun loadMoreHistory() {
        if (loadingMore || !hasMoreHistory || assistant != null) return
        loadingMore = true
        // 记住当前滚动位置（加载后跳回原位）
        val lm = messagesView.layoutManager as? LinearLayoutManager ?: return
        val firstVisiblePos = lm.findFirstVisibleItemPosition()
        // 当前列表顶部相对数据库的位置 = 总量 - 当前列表大小
        //   数据库倒序查询时 offset = 已加载条数 = adapter.itemCount
        val offset = adapter.itemCount
        lifecycleScope.launch {
            val older = ChatStore.loadMore(offset)  // 返回正序（更早的在前面）
            AppLog.i("Main", "分页加载：offset=$offset，查到 ${older.size} 条，hasMore=${older.size >= ChatStore.PAGE_SIZE}")
            if (older.isEmpty()) {
                hasMoreHistory = false
                loadingMore = false
                return@launch
            }
            val olderMsgs = older.map { m ->
                ChatMessage.create(
                    if (m.isFromUser) ChatMessage.Role.USER else ChatMessage.Role.ASSISTANT,
                    m.content,
                )
            }
            val merged = olderMsgs + adapter.currentList  // 旧的在前面
            adapter.submitAll(merged) {
                // ★ submitList 完成回调：跳回原位置（加上新加载的条数）
                val newPos = firstVisiblePos + olderMsgs.size
                messagesView.post {
                    (messagesView.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(newPos, 0)
                    loadingMore = false
                }
            }
            // 如果本次加载不足一页，说明已到底
            if (older.size < ChatStore.PAGE_SIZE) hasMoreHistory = false
        }
    }

    /** 处理 EXTRA_TEXT_PROMPT（从历史页点击消息继续提问）。 */
    private fun handlePromptExtra() {
        val intent = intent ?: return   // ★ 防御：intent 被 setIntent(null) 后不会 NPE
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
            // ★ 撤销按钮：仅 LISTENING 状态可用（可清除当前 partial 重新聆听）
            undoButton.isEnabled = (state == VoiceAssistant.State.LISTENING)
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
            streamedText.setLength(0)
            streamedReasoning.setLength(0)
            adapter.add(ChatMessage.create(ChatMessage.Role.USER, text))
            ChatStore.save(text, isFromUser = true)   // 落库
            // ★ 双重滚动：立即 + 延迟（应对布局刷新延迟）
            scrollToEnd(smooth = false)
            messagesView.postDelayed({ scrollToEnd(smooth = false) }, 200)
        }

        override fun onAssistantDelta(delta: String) = runOnUiThread {
            AppLog.i("Main", "onAssistantDelta: ${delta.length}字: \"${delta.take(20)}...\"")
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
            // ★ 第一次 text 到达时建气泡（和之前没 think 时一样的时机，保证在 USER 之后）
            //   同时把已累积的 reasoning 一起带上（思考内容已有但气泡刚建）
            if (curAssistantId == -1L) {
                val msg = ChatMessage.create(ChatMessage.Role.ASSISTANT, batch)
                    .copy(reasoning = streamedReasoning.toString().ifBlank { null })
                curAssistantId = msg.id
                streamedText.clear()
                adapter.add(msg)
                scrollToEnd(smooth = false)
            } else {
                // ★ 增量 append（不 setText 全量，避免高频重绘眼花）
                adapter.appendLastAssistant(batch)
                // ★ 思考结束开始正文：自动折叠思考区（正文给位，可点击再展开）
                adapter.collapseLastReasoning()
            }
            streamedText.append(batch)
        }

        override fun onAssistantComplete(text: String) = runOnUiThread {
            AppLog.i("Main", "onAssistantComplete: ${text.length} 字: \"${text.take(80)}\"")
            // ★ 先 flush 所有累积的 delta（确保流式片段不丢失）
            flushDeltas()
            // 以完整文本为准：覆盖或重建最后一条助手气泡，避免 delta 拼接不完整
            val final = text.trim()
            if (final.isEmpty()) return@runOnUiThread
            // ★ 只信任同步的 curAssistantId：last?.id 不可靠（AsyncListDiffer 异步）
            if (curAssistantId != -1L) {
                adapter.commitLastAssistant(final)   // ★ 覆盖 + 强制走 Markdown
                streamedText.setLength(0)
                streamedText.append(final)
                AppLog.i("Main", "提交助手气泡 (id=${curAssistantId})")
            } else {
                val msg = ChatMessage.create(ChatMessage.Role.ASSISTANT, final)
                    .copy(reasoning = streamedReasoning.toString().ifBlank { null })
                curAssistantId = msg.id
                streamedText.setLength(0)
                streamedText.append(final)
                adapter.add(msg)
                AppLog.i("Main", "新增助手气泡 (id=${curAssistantId})")
            }
            ChatStore.save(final, isFromUser = false)   // 落库
            scrollToEnd()
            // 保持在"进行中"状态，供下一轮 onUserText 重置（避免与尾部 delta 竞态）
        }

        override fun onError(message: String) = runOnUiThread { showError(message) }

        override fun onReasoningStart() = runOnUiThread {
            if (curAssistantId == -1L) stateText.text = "深度思考中…"
        }

        // ★ 思考过程增量：累积到当前助手气泡的折叠区
        override fun onReasoningDelta(delta: String) = runOnUiThread {
            AppLog.i("Main", "onReasoningDelta: ${delta.length}字: \"${delta.take(20)}...\"")
            if (delta.isBlank()) return@runOnUiThread
            // ★ 节流：复用 pendingDelta 同样的机制但单独累积 reasoning
            synchronized(pendingReasoning) {
                pendingReasoning.append(delta)
                if (flushReasoningRunnable == null) {
                    flushReasoningRunnable = Runnable { flushReasoning() }
                    uiHandler.postDelayed(flushReasoningRunnable!!, REASONING_FLUSH_INTERVAL_MS)
                }
            }
        }
    }

    /** ★ 思考增量节流缓冲（与正文 delta 独立）。 */
    private val pendingReasoning = StringBuilder()
    private var flushReasoningRunnable: Runnable? = null

    /** 取出累积的 reasoning。
     *  ★ 不建气泡！只累积到 streamedReasoning。等 text 第一次到达时（flushDeltas）才建气泡，
     *    这样气泡一定在 USER 消息之后（text 比 reasoning 晚到，天然保证顺序正确）。
     *    气泡已存在时（text 已开始流式），才调 updateLastReasoning 刷新折叠区。 */
    private fun flushReasoning() {
        val batch: String
        synchronized(pendingReasoning) {
            if (pendingReasoning.isEmpty()) { flushReasoningRunnable = null; return }
            batch = pendingReasoning.toString()
            pendingReasoning.clear()
            flushReasoningRunnable = null
        }
        // ★ reasoning 比 text 先到（思考在前）→ 第一次时建气泡，后续实时刷新折叠区
        //   顺序安全：onUserText 的 broadcast 在 handleLlmTurn 之前调用，Handler FIFO 保证
        //   USER 气泡先建；ChatAdapter backingList 同步，不会丢数据。
        if (curAssistantId == -1L) {
            val msg = ChatMessage.create(ChatMessage.Role.ASSISTANT, "")
                .copy(reasoning = batch)
            curAssistantId = msg.id
            streamedReasoning.clear()
            streamedReasoning.append(batch)
            adapter.add(msg)
            // ★ 新气泡出现时直接跳到底部（不 smooth，避免被高频重绘打断）
            scrollToEnd(smooth = false)
            messagesView.postDelayed({ scrollToEnd(smooth = false) }, 100)
        } else {
            streamedReasoning.append(batch)
            adapter.setLastReasoning(streamedReasoning.toString())
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

    /** ★ 错误提示：Snackbar（比 Toast 持久，可滑动/点按关闭；长消息可展开）。
     *  LLM/ASR/TTS 异常都用这里；"重试"按钮仅 LLM 错误时有意义（重新发送上一句）。 */
    private fun showError(message: String) {
        val root = findViewById<android.view.View>(R.id.messagesView)?.parent as? android.view.ViewGroup
            ?: findViewById(android.R.id.content)
        com.google.android.material.snackbar.Snackbar.make(
            root ?: window.decorView,
            android.text.Html.fromHtml("<b>⚠ </b>${android.text.Html.escapeHtml(message)}", 0),
            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
        ).setAction("关闭") { }.show()
    }
}
