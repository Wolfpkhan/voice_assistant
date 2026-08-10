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
        // ★ 启动时探测设备是否真支持 AEC（后台 IO，不阻塞）
        Thread {
            val result = com.sherva.voiceassistant.audio.AecProbe.probeAec(this)
            AppLog.i("App", "AEC 探测结果: available=${result.available}, reason=${result.reason}")
            val sp = getSharedPreferences("aec_probe", MODE_PRIVATE)
            sp.edit().putBoolean("available", result.available).putString("reason", result.reason).apply()
        }.apply { name = "aec-probe" }.start()
    }
}
