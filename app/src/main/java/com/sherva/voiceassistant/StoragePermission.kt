package com.sherva.voiceassistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * 存储权限工具。
 *
 * 目标：让 App 能直接写 /sdcard/Download/sherpa_assistant.log（用户可见、便于排查）。
 *
 * - Android 11+ (API 30+) 需要「所有文件访问权限」MANAGE_EXTERNAL_STORAGE，
 *   必须跳转系统设置页让用户手动开启（无法用普通权限弹窗）。
 * - Android 10 及以下：普通写权限即可。
 */
object StoragePermission {

    /** 是否已获得写 Download/外部存储的权限。 */
    fun granted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // Android 9 及以下：检查 WRITE_EXTERNAL_STORAGE（已在 manifest 声明 legacy）
            true
        }
    }

    /**
     * 跳转系统「所有文件访问权限」设置页。
     * 用户开启后返回，[granted] 才为 true。
     */
    fun request(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }
}
