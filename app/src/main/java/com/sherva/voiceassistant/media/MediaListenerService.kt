package com.sherva.voiceassistant.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import com.sherva.voiceassistant.AppLog

/**
 * ★ 媒体控制通知监听服务（仅作为 MediaSessionManager.getActiveSessions 的授权载体）。
 *
 * Android 机制：第三方 App 要枚举其它 App 的 MediaSession（控制 QQ音乐/喜马拉雅等），
 * 必须有一个 NotificationListenerService 组件 + 用户在系统设置授予「通知使用权」。
 * 本 Service 本身不处理任何通知，只是授权入口。
 *
 * 授权检查：MediaControllerHelper.hasNotificationAccess()
 * 授权引导：MediaControllerHelper.openAccessSettings()
 */
class MediaListenerService : NotificationListenerService() {
    companion object {
        const val TAG = "MediaListener"
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.i(TAG, "MediaListenerService 就绪（授权载体）")
    }

    /** 供 MediaSessionManager.getActiveSessions() 使用的组件名 */
    fun componentName(context: Context): ComponentName =
        ComponentName(context, MediaListenerService::class.java)
}
