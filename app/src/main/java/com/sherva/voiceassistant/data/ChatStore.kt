package com.sherva.voiceassistant.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 聊天记录管理（保存/加载/搜索/清空）。
 *
 * 单例：主界面/搜索页共用同一个 Room 实例。
 * 所有数据库操作在 IO 线程执行。
 */
object ChatStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dao: MessageDao

    fun initialize(context: Context) {
        if (::dao.isInitialized) return
        dao = AppDatabase.get(context).messageDao()
    }

    /** 保存一条消息（用户说/助手答，自动落库）。 */
    fun save(content: String, isFromUser: Boolean, onDone: (() -> Unit)? = null) {
        scope.launch {
            dao.insert(MessageEntity(content = content, timestamp = System.currentTimeMillis(), isFromUser = isFromUser))
            onDone?.invoke()
        }
    }

    /** 加载全部历史（按时间正序）。 */
    suspend fun loadAll(): List<MessageEntity> = dao.getAllMessages()

    /** 供备份导入使用。 */
    fun getDao(): MessageDao = dao

    /** 搜索历史（内容模糊匹配）。 */
    suspend fun search(query: String): List<MessageEntity> = dao.searchMessages(query)

    /** 清空全部历史。 */
    suspend fun clearAll() = dao.clearAll()
}
