package com.sherva.voiceassistant

import android.app.Application
import com.sherva.voiceassistant.pipeline.VoiceAssistant

/**
 * 应用入口：在任何 Activity 之前初始化日志与全局崩溃捕获。
 * （普通 App 没有 READ_LOGS 权限拿不到 logcat，所以崩溃信息只能自己写文件）
 */
class App : Application() {
    /** 共享的 VoiceAssistant 单例。
     *  Activity 和 Service 都读这一个实例，避免在悬浮球模式下双实例浪费资源。
     *  VoiceAssistant 本身体重 ~200MB（Kokoro 模型），绝不能并发两个。 */
    @Volatile var sharedAssistant: VoiceAssistant? = null

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

    companion object {
        /** 获取全局共享的 VoiceAssistant。 */
        fun getAssistant(ctx: android.content.Context): VoiceAssistant? {
            return (ctx.applicationContext as? App)?.sharedAssistant
        }

        /** 设置全局共享的 VoiceAssistant。 */
        fun setAssistant(ctx: android.content.Context, assistant: VoiceAssistant?) {
            (ctx.applicationContext as? App)?.sharedAssistant = assistant
        }
    }
}
