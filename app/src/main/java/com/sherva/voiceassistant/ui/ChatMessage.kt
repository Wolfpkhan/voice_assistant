package com.sherva.voiceassistant.ui

/**
 * 对话消息模型。
 *
 * ★ id 是主构造参数（非初始化器）：data class 的 copy() 会保留 id，
 *   不会重新生成新 id —— 这是流式追加气泡正确工作的关键。
 */
data class ChatMessage(
    val role: Role,
    val text: String,
    val id: Long,
) {
    enum class Role { USER, ASSISTANT }

    companion object {
        @Volatile private var counter = 0L
        private fun nextId() = synchronized(this) { ++counter }

        /** 创建新消息（自动分配递增 id）。 */
        fun create(role: Role, text: String) = ChatMessage(role, text, nextId())
    }
}
