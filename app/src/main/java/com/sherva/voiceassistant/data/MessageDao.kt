package com.sherva.voiceassistant.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 聊天记录 DAO（借鉴 hermes_chat_android 的 MessageDao）：
 * - getAllMessages(): Flow 响应式（数据库变化自动推送 UI）
 * - 分页加载：getLatestMessages / getMessagesPaginated（滚动到底加载更早）
 * - 搜索（分页）
 */
@Dao
interface MessageDao {

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Insert
    suspend fun insertAll(messages: List<MessageEntity>)

    /** 响应式：全部消息按时间正序。数据库任何变化都会推送。 */
    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<MessageEntity>>

    /** 最近 N 条（倒序，用于初始加载，再反转为正序）。 */
    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLatestMessages(limit: Int): List<MessageEntity>

    /** 分页：更早的消息（offset = 已加载条数）。返回倒序，调用方反转。 */
    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getMessagesPaginated(limit: Int, offset: Int): List<MessageEntity>

    /** 总数。 */
    @Query("SELECT COUNT(*) FROM messages")
    suspend fun getMessageCount(): Int

    /** 搜索（分页，倒序）。 */
    @Query("SELECT * FROM messages WHERE content LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun searchMessagesPaginated(query: String, limit: Int, offset: Int): List<MessageEntity>

    /** 搜索（全部）。 */
    @Query("SELECT * FROM messages WHERE content LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    suspend fun searchMessages(query: String): List<MessageEntity>

    @Query("DELETE FROM messages")
    suspend fun clearAll()

    @Delete
    suspend fun delete(message: MessageEntity)
}
