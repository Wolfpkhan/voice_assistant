package com.sherva.voiceassistant.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 聊天记录管理（借鉴 hermes_chat_android）：
 * - 响应式：messagesFlow 随数据库变化自动推送（保存/清空/导入后 UI 自动刷新）
 * - 分页：getLatest/loadMore 只取需要的条数，滚动加载更早历史
 * - 主列表：正序（时间先后）
 */
object ChatStore {
    /** 分页大小：主界面/历史页一次加载的条数。 */
    const val PAGE_SIZE = 50

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dao: MessageDao

    fun initialize(context: Context) {
        if (::dao.isInitialized) return
        dao = AppDatabase.get(context).messageDao()
    }

    /** 响应式消息流（正序）。UI 端 collect 即可自动跟随数据库变化。 */
    fun messagesFlow(): Flow<List<MessageEntity>> = dao.getAllMessages()

    /** 保存一条消息（用户说/助手答，自动落库）。 */
    fun save(content: String, isFromUser: Boolean) {
        scope.launch {
            dao.insert(MessageEntity(content = content, timestamp = System.currentTimeMillis(), isFromUser = isFromUser))
        }
    }

    /** 初始加载：最近 [PAGE_SIZE] 条（正序）。 */
    suspend fun loadLatest(): List<MessageEntity> =
        dao.getLatestMessages(PAGE_SIZE).reversed()

    /** 全部消息（正序）。用于备份导出。 */
    suspend fun loadAll(): List<MessageEntity> = dao.getAllMessages().first()

    /** 加载更早的 [PAGE_SIZE] 条（offset 为已加载条数）。返回正序（更早的在前面）。 */
    suspend fun loadMore(offset: Int): List<MessageEntity> =
        dao.getMessagesPaginated(PAGE_SIZE, offset).reversed()

    /** 总条数。 */
    suspend fun count(): Int = dao.getMessageCount()

    /** 搜索（分页，倒序=最新在前）。 */
    suspend fun search(query: String, offset: Int = 0): List<MessageEntity> =
        dao.searchMessagesPaginated(query, PAGE_SIZE, offset)

    /** 供备份导入使用。 */
    fun getDao(): MessageDao = dao

    /** 清空全部历史。 */
    suspend fun clearAll() = dao.clearAll()
}
