package com.sherva.voiceassistant

import android.app.Application

/**
 * 应用入口：在任何 Activity 之前初始化日志与全局崩溃捕获。
 * （普通 App 没有 READ_LOGS 权限拿不到 logcat，所以崩溃信息只能自己写文件）
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.i("App", "Application.onCreate 完成")
    }
}
