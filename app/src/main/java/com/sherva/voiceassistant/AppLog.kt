package com.sherva.voiceassistant

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用文件日志 + 全局未捕获异常捕获。
 *
 * 双写：
 *   1. 主日志 → getExternalFilesDir/sherpa_assistant.log
 *      （路径 /storage/emulated/0/Android/data/com.sherva.voiceassistant/files/）
 *      App 无需权限即可写，Termux 可直接 cat 读取。
 *   2. 镜像   → Download/sherpa_assistant.log（通过文件直写，Android 10 之前或可写，
 *      失败则跳过；用户也可用文件管理器查看）。
 *
 * 崩溃时把完整堆栈写入文件，方便排查（因为普通 App 没有 READ_LOGS 权限，拿不到 logcat）。
 */
object AppLog {
    private const val TAG = "AppLog"
    private const val FILE_NAME = "sherpa_assistant.log"

    @Volatile private var primary: File? = null
    @Volatile private var external: File? = null
    @Volatile private var mirror: File? = null
    private val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    /** 必须尽早调用（Application.onCreate）。 */
    fun init(context: Context) {
        val ctx = context.applicationContext
        // 主日志：filesDir（App 私有，始终可写、不受存储权限/沙盒影响）
        primary = File(ctx.filesDir, FILE_NAME)
        // 外部镜像：getExternalFilesDir（无需权限，Termux 可直接读）
        external = ctx.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }
        // Download 镜像：部分系统受限，失败不影响
        mirror = runCatching { File("/storage/emulated/0/Download", FILE_NAME) }.getOrNull()

        i(TAG, "================ App 启动 ================")
        i(TAG, "机型: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
        i(TAG, "ABI: ${Build.SUPPORTED_ABIS.joinToString(",")}")
        i(TAG, "主日志: ${primary?.absolutePath}")
        i(TAG, "外部镜像: ${external?.absolutePath}")
        i(TAG, "下载镜像: ${mirror?.absolutePath}")

        installCrashHandler()
    }

    /** 安装全局未捕获异常处理器：崩溃堆栈落盘。 */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            e(TAG, "★★★ 未捕获异常 (线程=${t.name}) ★★★", e)
            flush()
            previous?.uncaughtException(t, e)
        }
    }

    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        val time = ts.format(Date())
        val body = if (t != null) {
            val sw = StringWriter(); val pw = PrintWriter(sw); t.printStackTrace(pw)
            "$msg\n$sw"
        } else msg
        val line = "$time $level/$tag: $body\n"
        synchronized(lock) {
            runCatching { primary?.appendText(line) }
            runCatching { external?.appendText(line) }
            runCatching { mirror?.appendText(line) }
        }
        // 同时打 logcat（方便能抓到时用）
        when (level) {
            "E" -> Log.e(tag, msg, t)
            "W" -> Log.w(tag, msg, t)
            else -> Log.i(tag, msg)
        }
    }

    /** 强制刷新（崩溃路径里调用）。 */
    fun flush() { /* appendText 已即时落盘，留作扩展 */ }
}
