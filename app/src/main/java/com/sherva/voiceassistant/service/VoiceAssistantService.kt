package com.sherva.voiceassistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.sherva.voiceassistant.App
import com.sherva.voiceassistant.AppLog
import com.sherva.voiceassistant.MainActivity
import com.sherva.voiceassistant.R
import com.sherva.voiceassistant.pipeline.VoiceAssistant

/**
 * 前台服务：在后台保活语音管线，同时显示悬浮球。
 *
 * - 持有独立的 VoiceAssistant 实例（与 Activity 无关）
 * - 悬浮球点击：开始/停止语音对话
 * - 前台通知：保活 + 显示当前状态
 *
 * Activity 通过 startService(Intent ACTION_START/ACTION_STOP) 控制。
 */
class VoiceAssistantService : Service() {

    companion object {
        const val TAG = "VAService"
        const val ACTION_START = "com.sherva.voiceassistant.START"
        const val ACTION_STOP = "com.sherva.voiceassistant.STOP"
        const val ACTION_TOGGLE = "com.sherva.voiceassistant.TOGGLE"
        private const val CHANNEL_ID = "voice_assistant_channel"
        private const val NOTIF_ID = 10086

        /** 单例引用：悬浮球和外部查询状态用 */
        @Volatile var instance: VoiceAssistantService? = null
    }

    private var assistant: VoiceAssistant? = null
    private var ball: FloatingBallManager? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        AppLog.i(TAG, "Service onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AppLog.i(TAG, "收到 STOP")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                AppLog.i(TAG, "收到 TOGGLE")
                toggleVoice()
            }
            ACTION_START, null -> {
                AppLog.i(TAG, "收到 START")
                if (!started) startVoice()
            }
        }
        return START_STICKY  // 被杀后自动重启
    }

    /** 启动前台服务 + 语音助手 + 悬浮球。 */
    private fun startVoice() {
        started = true
        startForegroundCompat()
        // ★ 接管 App.sharedAssistant（避免与 Activity 双实例）
        val existing = App.getAssistant(this)
        if (existing != null) {
            AppLog.i(TAG, "接管 App.sharedAssistant（状态=${existing.state}）")
            existing.addListener(serviceListener)  // 多 listener 广播，Activity 也同时收到
            assistant = existing
        } else {
            AppLog.i(TAG, "App.sharedAssistant 为空，创建新实例")
            val config = com.sherva.voiceassistant.MainActivity.buildServiceConfig(this)
            val a = VoiceAssistant(this, config)
            a.addListener(serviceListener)
            App.setAssistant(this, a)
            assistant = a
        }
        assistant?.startConversation()
        // 悬浮球
        if (Settings.canDrawOverlays(this)) {
            ball = FloatingBallManager(this) { toggleVoice() }.also { it.show() }
        } else {
            AppLog.w(TAG, "未获得悬浮窗权限，跳过悬浮球")
        }
    }

    /** 切换：聆听 ↔ 停止。 */
    private fun toggleVoice() {
        val a = assistant ?: return
        if (a.state == VoiceAssistant.State.IDLE) {
            AppLog.i(TAG, "TOGGLE → 开始对话")
            a.startConversation()
        } else {
            AppLog.i(TAG, "TOGGLE → 停止对话")
            a.stop()
        }
    }

    private fun stopVoice() {
        // ★ 不 release 全局实例：Activity 退出后只是 Service 独享，下一次 onResume 会重新接管
        assistant?.removeListener(serviceListener)
        assistant?.stop()
        // 只清本地引用，释放悬浮球
        ball?.dismiss()
        ball = null
        assistant = null
    }

    // ---------- 前台通知 ----------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "语音助手后台服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持语音对话在后台运行"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat() {
        val notif = buildNotification("语音助手运行中")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ 必须指定 foregroundServiceType
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, VoiceAssistantService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("灵犀语音助手")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stop, "停止", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    // ---------- 悬浮球委托给 FloatingBallManager ----------

    private fun updateBallState(state: VoiceAssistant.State) {
        ball?.setState(state)
        updateNotification(when (state) {
            VoiceAssistant.State.IDLE -> "待机"
            VoiceAssistant.State.LISTENING -> "正在聆听..."
            VoiceAssistant.State.THINKING -> "正在思考..."
            VoiceAssistant.State.SPEAKING -> "正在播报..."
        })
    }

    // ---------- VoiceAssistant 回调（简化版，不更新 Activity UI）----------

    private val serviceListener = object : VoiceAssistant.Listener {
        override fun onState(state: VoiceAssistant.State) {
            AppLog.i(TAG, "状态变更: $state")
            updateBallState(state)
        }
        override fun onPartialText(text: String) {}
        override fun onUserText(text: String) {}
        override fun onAssistantDelta(delta: String) {}
        override fun onAssistantComplete(text: String) {}
        override fun onReasoningStart() {}
        override fun onError(msg: String) {
            AppLog.e(TAG, "语音错误: $msg")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLog.i(TAG, "Service onDestroy")
        stopVoice()
        instance = null
    }
}
