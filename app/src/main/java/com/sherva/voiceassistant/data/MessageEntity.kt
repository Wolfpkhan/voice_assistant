package com.sherva.voiceassistant.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 聊天记录实体（借鉴 hermes_chat_android 的 MessageEntity）。
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val content: String,
    val timestamp: Long,
    val isFromUser: Boolean,
)
