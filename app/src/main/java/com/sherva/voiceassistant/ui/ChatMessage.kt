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
    /** ★ 思考过程全文（reasoning 模型才有，普通模型为 null）。可折叠展示。 */
    val reasoning: String? = null,
    /** ★ 工具调用列表（agent 类调用才有）。可折叠展示。 */
    val toolCalls: List<ToolCallDisplay> = emptyList(),
) {
    enum class Role { USER, ASSISTANT, NOTICE }

    companion object {
        @Volatile private var counter = 0L
        private fun nextId() = synchronized(this) { ++counter }

        /** 创建新消息（自动分配递增 id）。 */
        fun create(role: Role, text: String) = ChatMessage(role, text, nextId())
    }
}

/**
 * 工具调用摘要（用于气泡内显示）。
 * @param name    工具名（bash / read / grep / ...）
 * @param argsPreview 参数预览（截断到 80 字）
 * @param status  执行状态：running / done / error
 */
data class ToolCallDisplay(
    val name: String,
    val argsPreview: String,
    val status: String,
)