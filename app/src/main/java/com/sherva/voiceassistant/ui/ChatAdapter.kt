package com.sherva.voiceassistant.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sherva.voiceassistant.R

/**
 * 对话列表适配器：助手消息（左，带头像）+ 用户消息（右，圆角气泡）。
 * 参考 ChatGPT 客户端布局。
 */
class ChatAdapter : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_ASSISTANT = 1
        private const val TYPE_USER = 2
        private const val TYPE_NOTICE = 3
        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(a: ChatMessage, b: ChatMessage) = a.id == b.id
            override fun areContentsTheSame(a: ChatMessage, b: ChatMessage) =
                a.role == b.role && a.text == b.text
        }
    }

    private class AssistantVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.messageText)
        // ★ 思考过程折叠区（默认隐藏）
        val reasoningHeader: TextView = v.findViewById(R.id.reasoningHeader)
        val reasoningText: TextView = v.findViewById(R.id.reasoningText)
        var expanded = false
    }
    private class UserVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.messageText)
    }
    private class NoticeVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.noticeText)
    }

    override fun getItemViewType(position: Int): Int =
        when (getItem(position).role) {
            ChatMessage.Role.USER -> TYPE_USER
            ChatMessage.Role.NOTICE -> TYPE_NOTICE
            else -> TYPE_ASSISTANT
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> UserVH(inf.inflate(R.layout.item_message_user, parent, false))
            TYPE_NOTICE -> NoticeVH(inf.inflate(R.layout.item_message_notice, parent, false))
            else -> {
                val vh = AssistantVH(inf.inflate(R.layout.item_message_assistant, parent, false))
                // ★ 点击思考区头部展开/折叠
                vh.reasoningHeader.setOnClickListener {
                    vh.expanded = !vh.expanded
                    notifyItemChanged(vh.bindingAdapterPosition)
                }
                vh
            }
        }
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        val m = getItem(position)
        when (h) {
            is AssistantVH -> {
                MarkdownRenderer.render(h.text, m.text)
                // ★ 思考过程：有内容才显示折叠区
                val rs = m.reasoning
                if (!rs.isNullOrBlank()) {
                    h.reasoningHeader.visibility = View.VISIBLE
                    h.reasoningHeader.text = if (h.expanded) "▼ 思考过程" else "▶ 思考过程"
                    h.reasoningText.text = rs
                    h.reasoningText.visibility = if (h.expanded) View.VISIBLE else View.GONE
                } else {
                    h.reasoningHeader.visibility = View.GONE
                    h.reasoningText.visibility = View.GONE
                }
            }
            is UserVH -> MarkdownRenderer.render(h.text, m.text)
            is NoticeVH -> h.text.text = m.text
        }
    }

    /** 追加一条消息（滚动由调用方处理）。 */
    fun add(msg: ChatMessage) = submitList(currentList + msg)

    /** 替换或追加：若 [msg] 的 id 已存在则更新，否则追加。 */
    fun upsert(msg: ChatMessage) {
        val list = currentList.toMutableList()
        val idx = list.indexOfFirst { it.id == msg.id }
        if (idx >= 0) list[idx] = msg else list.add(msg)
        submitList(list)
    }

    /** 更新最后一条助手消息（用于流式增量），没有则新增。 */
    fun updateLastAssistant(text: String) {
        val list = currentList.toMutableList()
        val idx = list.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
        if (idx >= 0) {
            // ★ copy() 保留 id 和 reasoning（id 是主构造参数），DiffUtil 识别为同一 item → 原地更新不新增
            list[idx] = list[idx].copy(text = text)
        } else {
            list.add(ChatMessage.create(ChatMessage.Role.ASSISTANT, text))
        }
        submitList(list)
    }

    /** ★ 追加最后一条助手消息的思考过程（用于 reasoning 流式增量）。 */
    fun updateLastReasoning(delta: String) {
        val list = currentList.toMutableList()
        val idx = list.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
        if (idx >= 0) {
            val cur = list[idx].reasoning ?: ""
            list[idx] = list[idx].copy(reasoning = cur + delta)
            submitList(list)
        }
    }

    fun clearAll() = submitList(emptyList())

    /** 一次性替换整个列表（避免 clearAll+逐个 add 的异步竞态叠加）。 */
    fun submitAll(list: List<ChatMessage>, onCommit: Runnable? = null) =
        submitList(list, onCommit)
}
