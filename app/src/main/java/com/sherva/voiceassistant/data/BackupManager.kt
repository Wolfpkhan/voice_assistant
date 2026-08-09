package com.sherva.voiceassistant.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 备份导入导出（借鉴 hermes_chat_android 的 BackupDataSourceImpl）。
 *
 * - 导出：消息 → JSON → GZIP 压缩 → 写入用户选择的 Uri（SAF）
 * - 导入：从 Uri 读 → 自动识别 gzip/raw → 解析 → 入库
 */
object BackupManager {

    /**
     * 导出全部消息到 [destinationUri]（调用方用 SAF CreateDocument 获取）。
     * @return 导出的消息条数
     */
    suspend fun export(
        context: Context,
        destinationUri: Uri,
        messages: List<MessageEntity>,
    ): Int = withContext(Dispatchers.IO) {
        val json = BackupSerializer.serialize(messages, appVersion(context))
        context.contentResolver.openOutputStream(destinationUri)?.use { out ->
            GZIPOutputStream(out).buffered().use { it.write(json.toByteArray()) }
        } ?: throw IllegalStateException("无法写入目标文件")
        messages.size
    }

    /**
     * 从 [sourceUri] 导入备份。
     * @param replace 为 true 时清空现有记录，false 时追加
     * @return 导入的消息条数
     */
    suspend fun import(
        context: Context,
        sourceUri: Uri,
        dao: MessageDao,
        replace: Boolean,
    ): Int = withContext(Dispatchers.IO) {
        val json = readBackupFile(context, sourceUri)
        val messages = BackupSerializer.deserialize(json)
        if (replace) dao.clearAll()
        messages.forEach { dao.insert(it) }
        messages.size
    }

    /** 读取备份文件（先试 gzip，失败退回 raw json）。 */
    private fun readBackupFile(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("无法打开备份文件")
        return try {
            GZIPInputStream(BufferedInputStream(bytes.inputStream())).buffered().use {
                it.readBytes().decodeToString()
            }
        } catch (_: Exception) {
            bytes.decodeToString()
        }
    }

    private fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (_: Exception) { "unknown" }
}
