package com.sherva.voiceassistant.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.provider.Settings
import com.sherva.voiceassistant.AppLog

/**
 * ★ 本地媒体控制（替代之前依赖 TermuxRemoteFrontend 的 /media_pause_all 等 HTTP API）。
 *
 * 能力：枚举活跃 MediaSession，暂停/恢复外部音乐 App（QQ音乐/喜马拉雅/网易云/...）。
 * 前提：用户授予「通知使用权」（MediaListenerService 作为授权载体）。
 *
 * 简化版状态机（单控制器，无需 caller 分桶）：
 *   pauseAll()  → 对 state=PLAYING/BUFFERING 的外部 session 发 pause，记入 pausedByMe
 *   resumeAll() → 只恢复 pausedByMe 记录的包；
 *                 记录为空且距上次 pause ≤30s 时 fallback（对 state=PAUSED 的活跃
 *                 session 发 play——兜底 SysTTS/提示音抢焦点停了音乐但无记录的场景）
 *
 * 保留的三道安全门（沿用 FI 双桶版验证过的语义）：
 *   1. state 过滤：不碰 STOPPED(1)/ERROR(7)/inactive(-1)，避免意外启动后台挂着不听的 App
 *   2. 时间窗：fallback 仅 30s 内有效
 *   3. 幂等：pauseAll 没停任何东西时不覆盖旧记录/时间戳
 */
object MediaControllerHelper {

    private const val TAG = "MediaCtl"
    /** fallback 生效时间窗 */
    private const val FALLBACK_WINDOW_MS = 30_000L
    /** MediaSession PlaybackState.state 常量 */
    private const val ST_STOPPED = 1
    private const val ST_PAUSED = 2
    private const val ST_PLAYING = 3
    private const val ST_FAST_FORWARDING = 4
    private const val ST_REWINDING = 5
    private const val ST_BUFFERING = 6
    private const val ST_ERROR = 7

    /** 我暂停的包名（唯一记录，让路恢复只恢复这些） */
    @Volatile private var pausedByMe: List<String> = emptyList()
    /** 上次实际暂停（非空）的时刻；0 = 从未 */
    @Volatile private var pauseTime: Long = 0L

    data class Result(val ok: Boolean, val paused: List<String>, val restarted: List<String>, val note: String)

    // ---------- 权限 ----------

    fun hasNotificationAccess(context: Context): Boolean =
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.contains(context.packageName) == true

    /** 跳系统「通知使用权」设置页（用户手动勾选本 App） */
    fun openAccessSettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppLog.w(TAG, "打开通知使用权设置失败: ${e.message}")
        }
    }

    // ---------- 核心 ----------

    private fun sessions(context: Context): List<MediaController> {
        if (!hasNotificationAccess(context)) throw SecurityException("未授予通知使用权")
        val sm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val cn = ComponentName(context, MediaListenerService::class.java)
        return sm.getActiveSessions(cn).filter { it.packageName != context.packageName }
    }

    /** 暂停所有正在播放的外部 MediaSession（QQ音乐/喜马拉雅/...）。 */
    fun pauseAll(context: Context): Result {
        return try {
            val paused = mutableListOf<String>()
            for (c in sessions(context)) {
                val st = c.playbackState?.state ?: -1
                if (st == ST_PLAYING || st == ST_FAST_FORWARDING || st == ST_BUFFERING) {
                    try {
                        c.transportControls.pause()
                        paused.add(c.packageName)
                        AppLog.i(TAG, "暂停 ${c.packageName} (st=$st)")
                    } catch (e: Exception) {
                        AppLog.w(TAG, "暂停 ${c.packageName} 失败: ${e.message}")
                    }
                }
            }
            // 幂等：没停任何东西不动记录（避免空场景覆盖）
            if (paused.isNotEmpty()) {
                pausedByMe = paused
                pauseTime = System.currentTimeMillis()
            }
            Result(true, paused, emptyList(), if (paused.isEmpty()) "no active playing session" else "paused ${paused.size}")
        } catch (e: SecurityException) {
            AppLog.w(TAG, "pauseAll 无权限: ${e.message}")
            Result(false, emptyList(), emptyList(), "no notification access")
        } catch (e: Exception) {
            AppLog.w(TAG, "pauseAll 失败: ${e.message}")
            Result(false, emptyList(), emptyList(), e.message ?: "error")
        }
    }

    /** 恢复（只恢复我暂停的；无记录时 30s 窗口内 fallback 恢复 paused 状态的）。 */
    fun resumeAll(context: Context): Result {
        return try {
            val list = sessions(context)
            val restarted = mutableListOf<String>()
            val fallback = pausedByMe.isEmpty() &&
                (if (pauseTime == 0L) false else System.currentTimeMillis() - pauseTime <= FALLBACK_WINDOW_MS)

            if (!fallback) {
                // 精准恢复：只恢复我记录的包
                for (c in list) {
                    if (c.packageName in pausedByMe) {
                        try {
                            c.transportControls.play()
                            restarted.add(c.packageName)
                            AppLog.i(TAG, "恢复 ${c.packageName}")
                        } catch (e: Exception) {
                            AppLog.w(TAG, "恢复 ${c.packageName} 失败: ${e.message}")
                        }
                    }
                }
            } else {
                // fallback：对 state=PAUSED/BUFFERING 的活跃 session 发 play
                for (c in list) {
                    val st = c.playbackState?.state ?: -1
                    if (st == ST_PAUSED || st == ST_BUFFERING) {
                        try {
                            c.transportControls.play()
                            restarted.add(c.packageName)
                            AppLog.i(TAG, "fallback 恢复 ${c.packageName} (st=$st)")
                        } catch (e: Exception) {
                            AppLog.w(TAG, "fallback ${c.packageName} 失败: ${e.message}")
                        }
                    }
                }
            }
            pausedByMe = emptyList()
            // ★ 周期闭合：清 pauseTime，后续多余的 resume 不再触发 fallback——
            //   否则一次 pause-resume 后，待机恢复等后续调用仍在 30s 窗口内，
            //   会把用户手动暂停的播放器当"被抢焦点停的"拉起来
            pauseTime = 0L
            val note = when {
                fallback && restarted.isNotEmpty() -> "fallback: resumed paused sessions"
                fallback -> "fallback window with nothing to resume"
                else -> "resumed ${restarted.size} recorded"
            }
            Result(true, emptyList(), restarted, note)
        } catch (e: SecurityException) {
            AppLog.w(TAG, "resumeAll 无权限: ${e.message}")
            Result(false, emptyList(), emptyList(), "no notification access")
        } catch (e: Exception) {
            AppLog.w(TAG, "resumeAll 失败: ${e.message}")
            Result(false, emptyList(), emptyList(), e.message ?: "error")
        }
    }

    /** 诊断：列出当前活跃 session（状态/包名/标题）。 */
    fun dump(context: Context): String = try {
        sessions(context).joinToString("; ") {
            "${it.packageName}(${it.playbackState?.state ?: -1})"
        }.ifEmpty { "(no session)" }
    } catch (e: Exception) {
        "dump 失败: ${e.message}"
    }
}
