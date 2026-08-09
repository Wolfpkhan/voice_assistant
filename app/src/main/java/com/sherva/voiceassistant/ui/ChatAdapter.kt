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
        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(a: ChatMessage, b: ChatMessage) = a.id == b.id
            override fun areContentsTheSame(a: ChatMessage, b: ChatMessage) =
                a.role == b.role && a.text == b.text
        }
    }

    private class AssistantVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.messageText)
    }
    private class UserVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.messageText)
    }

    override fun getItemViewType(position: Int): Int =
        if (getItem(position).role == ChatMessage.Role.USER) TYPE_USER else TYPE_ASSISTANT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> UserVH(inf.inflate(R.layout.item_message_user, parent, false))
            else -> AssistantVH(inf.inflate(R.layout.item_message_assistant, parent, false))
        }
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        val m = getItem(position)
        when (h) {
            is UserVH -> h.text.text = m.text
            is AssistantVH -> h.text.text = m.text
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
            // ★ copy() 保留 id（id 是主构造参数），DiffUtil 识别为同一 item → 原地更新不新增
            list[idx] = list[idx].copy(text = text)
        } else {
            list.add(ChatMessage.create(ChatMessage.Role.ASSISTANT, text))
        }
        submitList(list)
    }

    fun clearAll() = submitList(emptyList())
}
