package com.sherva.voiceassistant.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.sherva.voiceassistant.R

/**
 * ★ 权限清单与状态检测（唯一事实来源）。
 *
 * 分三档：
 *  REQUIRED  核心功能（语音链路）必需，不授权 App 基本不可用
 *  ENHANCED  增强功能（悬浮窗/日志/媒体控制），不授权只降级不阻塞
 *  AUTO      普通权限安装即授予，无需引导
 */
object PermissionCenter {

    enum class Level { REQUIRED, ENHANCED, AUTO }

    /** 一项权限的完整描述 */
    data class Item(
        val key: String,               // 稳定标识（记录"不再提示"用）
        val icon: String,              // emoji 图标
        val titleRes: Int,             // 标题
        val descRes: Int,              // 为什么需要（用户视角）
        val level: Level,
        val grantedNow: (Context) -> Boolean,
        val request: (Activity) -> Unit,   // 跳转/请求
    )

    /** 运行时弹窗组（录音 + 通知），标准 requestPermissions 流（onResume 重开对话框刷新） */
    fun requestRuntimeBasics(activity: Activity) {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        activity.requestPermissions(perms.toTypedArray(), 100)
    }

    fun list(context: Context): List<Item> = listOf(
        // ---------- 必需 ----------
        Item(
            key = "mic",
            icon = "🎤",
            titleRes = R.string.perm_mic_title,
            descRes = R.string.perm_mic_desc,
            level = Level.REQUIRED,
            grantedNow = {
                ContextCompat.checkSelfPermission(it, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            },
            request = { activity ->
                requestRuntimeBasics(activity)
            },
        ),
        Item(
            key = "notif",
            icon = "🔔",
            titleRes = R.string.perm_notif_title,
            descRes = R.string.perm_notif_desc,
            level = Level.REQUIRED,
            grantedNow = {
                Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(it, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
            },
            request = { activity ->
                if (Build.VERSION.SDK_INT >= 33)
                    activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            },
        ),
        // ---------- 增强 ----------
        Item(
            key = "overlay",
            icon = "👁",
            titleRes = R.string.perm_overlay_title,
            descRes = R.string.perm_overlay_desc,
            level = Level.ENHANCED,
            grantedNow = { Settings.canDrawOverlays(it) },
            request = { activity ->
                activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        ),
        Item(
            key = "storage",
            icon = "📝",
            titleRes = R.string.perm_storage_title,
            descRes = R.string.perm_storage_desc,
            level = Level.ENHANCED,
            grantedNow = { android.os.Environment.isExternalStorageManager() },
            request = { activity ->
                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${activity.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        ),
        Item(
            key = "media",
            icon = "🎵",
            titleRes = R.string.perm_media_title,
            descRes = R.string.perm_media_desc,
            level = Level.ENHANCED,
            grantedNow = {
                Settings.Secure.getString(it.contentResolver, "enabled_notification_listeners")
                    ?.contains(it.packageName) == true
            },
            request = { activity ->
                activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        ),
    )

    /** 首启动是否还缺必需权限 */
    fun missingRequired(context: Context): List<Item> =
        list(context).filter { it.level == Level.REQUIRED && !it.grantedNow(context) }

    /** 全部未授权项（含增强） */
    fun missingAll(context: Context): List<Item> =
        list(context).filter { !it.grantedNow(context) }

}
