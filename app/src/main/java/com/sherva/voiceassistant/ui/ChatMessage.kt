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
    /** ★ 是否已定稿（onAssistantComplete 后）。true 时 onBindViewHolder 强制走 Markdown。
     *   背景：流式累积文本 == trim 后 final 时 DiffUtil 判定内容未变不重绑，
     *   TextView 停留在"首批 Markdown + 后续纯文本 append"混合态。committed
     *   参与内容比对，保证 commit 后必重绑必渲染。 */
    val committed: Boolean = false,
    /** ★ 消息时间（毫秒 epoch）。0=未设（不显示）。气泡底部小字展示。 */
    val timestamp: Long = 0L,
) {
    enum class Role { USER, ASSISTANT, NOTICE }

    companion object {
        @Volatile private var counter = 0L
        private fun nextId() = synchronized(this) { ++counter }

        /** 创建新消息（自动分配递增 id）。 */
        fun create(role: Role, text: String) = ChatMessage(role, text, nextId())

        /** ★ 创建新消息（带时间戳，历史加载/实时显示时间用）。 */
        fun create(role: Role, text: String, timestamp: Long) = ChatMessage(role, text, nextId(), timestamp = timestamp)
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