package com.sherva.voiceassistant.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sherva.voiceassistant.AppLog
import com.sherva.voiceassistant.R

/**
 * 对话列表适配器：助手消息（左，带头像）+ 用户消息（右，圆角气泡）。
 * 参考 ChatGPT 客户端布局。
 */
class ChatAdapter : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DIFF) {

    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        super.onAttachedToRecyclerView(rv)
        recyclerView = rv
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        super.onDetachedFromRecyclerView(rv)
        recyclerView = null
    }

    companion object {
        private const val TYPE_ASSISTANT = 1
        private const val TYPE_USER = 2
        private const val TYPE_NOTICE = 3
        /** ★ notifyItemChanged payload：强制下次 bind 走 Markdown（用于 onAssistantComplete）。 */
        const val PAYLOAD_FORCE_MARKDOWN = "force_markdown"
        /** ★ notifyItemChanged payload：只重渲染折叠区（用于 setLastReasoning）。 */
        const val PAYLOAD_REASONING = "reasoning_only"
        /** ★ payload：只重渲染工具调用区（用于 setLastToolCalls）。 */
        const val PAYLOAD_TOOLS = "tools_only"
        /** ★ payload：折叠思考区（思考结束开始正文时）。 */
        const val PAYLOAD_COLLAPSE = "collapse_reasoning"
        /** ★ payload：只刷新 TTS 播报按钮状态。 */
        const val PAYLOAD_TTS_STATE = "tts_state"
        /** ★ 增量追加正文（流式 delta，只 append 不重渲染，避免高频闪烁） */
        data class PayloadAppendText(val delta: String)
        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(a: ChatMessage, b: ChatMessage) = a.id == b.id
            override fun areContentsTheSame(a: ChatMessage, b: ChatMessage) =
                a.role == b.role && a.text == b.text && a.reasoning == b.reasoning && a.toolCalls == b.toolCalls && a.committed == b.committed
        }
    }

    private class AssistantVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.messageText)
        val time: TextView = v.findViewById(R.id.timeText)
        // ★ 思考过程折叠区（默认隐藏）
        val reasoningHeader: TextView = v.findViewById(R.id.reasoningHeader)
        val reasoningText: TextView = v.findViewById(R.id.reasoningText)
        // ★ 工具调用折叠区（默认隐藏）
        val toolsHeader: TextView = v.findViewById(R.id.toolsHeader)
        val toolsText: TextView = v.findViewById(R.id.toolsText)
        // ★ 手动 TTS 播报按钮（🔊/⏹）
        val ttsBtn: TextView = v.findViewById(R.id.ttsButton)
        var expanded = true   // ★ 默认展开，让用户看到实时思考过程
        /** ★ 工具调用区展开状态。 */
        var toolsExpanded = false   // ★ 默认折叠——完成后才展开看
        /** ★ 上一轮 onBind 时的文本长度：用于检测流式增量。 */
        var lastBoundTextLen: Int = 0
        /** ★ 是否上一轮走的是 Markdown（用于决定本次是否重渲染）。 */
        var lastWasMarkdown: Boolean = false
    }
    private class UserVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.messageText)
        val time: TextView = v.findViewById(R.id.timeText)
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
                // ★ 点击思考区头部展开/折叠：直接改 View，不走 notifyItemChanged
                //   （避免完整重绑导致 ViewHolder 被替换成新的 expanded=true 默认值，需点两次）
                vh.reasoningHeader.setOnClickListener {
                    vh.expanded = !vh.expanded
                    vh.reasoningHeader.text = if (vh.expanded) vh.itemView.context.getString(R.string.reasoning_hide) else vh.itemView.context.getString(R.string.reasoning_toggle)
                    vh.reasoningText.visibility = if (vh.expanded) View.VISIBLE else View.GONE
                }
                // ★ 工具调用区头部点击展开/折叠
                vh.toolsHeader.setOnClickListener {
                    vh.toolsExpanded = !vh.toolsExpanded
                    vh.toolsText.visibility = if (vh.toolsExpanded) View.VISIBLE else View.GONE
                }
                vh
            }
        }
    }

    private val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    private val dateFmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())

    /** ★ 当前手动播报中的消息 id（-1=无）。设后局部刷新对应气泡按钮。 */
    private var playingMsgId: Long = -1L

    /** ★ 手动播报回调：点击 🔊 开始 / 点击 ⏹ 停止。由 MainActivity 实现。 */
    var onToggleTts: ((msgId: Long, text: String) -> Unit)? = null

    /** ★ 外部更新播报状态（MainActivity 播放开始/结束/停止时调）。 */
    fun setPlayingMsgId(msgId: Long) {
        val old = playingMsgId
        playingMsgId = msgId
        // 局部刷新新旧两个气泡的按钮
        val oldIdx = backingList.indexOfFirst { it.id == old }
        if (oldIdx >= 0) notifyItemChanged(oldIdx, PAYLOAD_TTS_STATE)
        val newIdx = backingList.indexOfFirst { it.id == msgId }
        if (newIdx >= 0) notifyItemChanged(newIdx, PAYLOAD_TTS_STATE)
    }

    private fun bindTtsButton(h: AssistantVH, m: ChatMessage) {
        val playing = m.id == playingMsgId
        h.ttsBtn.text = if (playing) "⏹" else "🔊"
        h.ttsBtn.setOnClickListener {
            onToggleTts?.invoke(m.id, m.text)
        }
    }

    /** 毫秒 epoch → 当天零点（本地时区），用于跨天判断。 */
    private fun dayOf(ts: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = ts
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** ★ 气泡底部时间小字。
     *  合并规则：仅【同角色】且同分钟才隐藏（用户/助手分居两侧，各自该有
     *  可查的时间；跨角色即使同分钟也各自显示）。
     *  同天 HH:mm，跨天（相对上一条可见消息）MM-dd HH:mm；timestamp=0 隐藏。 */
    private fun renderTime(h: RecyclerView.ViewHolder, m: ChatMessage, position: Int) {
        val tv = when (h) {
            is AssistantVH -> h.time
            is UserVH -> h.time
            else -> return
        }
        if (m.timestamp <= 0L) { tv.visibility = View.GONE; return }
        if (position > 0) {
            val prev = getItem(position - 1)
            val sameRoleSameMin = prev.role == m.role && prev.timestamp > 0 &&
                (prev.timestamp / 60000L) == (m.timestamp / 60000L)
            if (sameRoleSameMin) { tv.visibility = View.GONE; return }
        }
        tv.visibility = View.VISIBLE
        // ★ 跨天相对上一条可见消息：带日期；今天内只显时刻
        val prevTs = if (position > 0) getItem(position - 1).timestamp else 0L
        tv.text = if (prevTs > 0 && dayOf(prevTs) != dayOf(m.timestamp))
            dateFmt.format(java.util.Date(m.timestamp))
        else
            timeFmt.format(java.util.Date(m.timestamp))
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        onBindViewHolder(h, position, emptyList())
    }

    /**
     * 带 payload 的 bind：支持 PAYLOAD_FORCE_MARKDOWN 强制走 Markdown（onAssistantComplete 用）。
     * 流式增量不带 payload，走默认增量检测逻辑。
     */
    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
        val m = getItem(position)
        when (h) {
            is AssistantVH -> {
                val rs = m.reasoning
                // ★ 折叠思考区（思考结束开始正文时）：设 expanded=false 并直接改 View
                if (payloads.contains(PAYLOAD_COLLAPSE)) {
                    h.expanded = false
                    h.reasoningHeader.text = h.itemView.context.getString(R.string.reasoning_toggle)
                    h.reasoningText.visibility = View.GONE
                    return
                }
                // ★ 增量追加正文：只 append delta 到 TextView，不走 Markdown 不 submitList
                val appendDelta = payloads.filterIsInstance<PayloadAppendText>().firstOrNull()
                if (appendDelta != null) {
                    h.text.append(appendDelta.delta)
                    return
                }
                // ★ 仅重渲染折叠区（reasoning 增量专用 payload，不动 text）
                if (payloads.contains(PAYLOAD_REASONING)) {
                    if (!rs.isNullOrBlank()) {
                        h.reasoningHeader.visibility = View.VISIBLE
                        h.reasoningHeader.text = if (h.expanded) h.itemView.context.getString(R.string.reasoning_hide) else h.itemView.context.getString(R.string.reasoning_toggle)
                        h.reasoningText.text = rs
                        h.reasoningText.visibility = if (h.expanded) View.VISIBLE else View.GONE
                    }
                    return
                }
                // ★ 仅重渲染工具调用区（不动 reasoning/text）
                if (payloads.contains(PAYLOAD_TOOLS)) {
                    renderTools(h, m.toolCalls)
                    return
                }
                // ★ 仅刷新 TTS 播报按钮状态
                if (payloads.contains(PAYLOAD_TTS_STATE)) {
                    bindTtsButton(h, m)
                    return
                }
                val forceMarkdown = payloads.contains(PAYLOAD_FORCE_MARKDOWN) || m.committed
                val newText = m.text
                if (forceMarkdown) {
                    // ★ 定稿（committed=true）或强制 payload：必走 Markdown。
                    //   修复：流式累积文本 == trim 后 final 时 DiffUtil 不重绑，
                    //   混合态（首批 MD + 后续纯文本）永远不修复的随机 bug。
                    if (h.lastWasMarkdown && h.lastBoundTextLen == newText.length) {
                        // 已是同长度 Markdown 渲染，跳过重渲（防闪烁）
                    } else {
                        AppLog.i("ChatAdapter", "Markdown 渲染(定稿): ${newText.length}字")
                        MarkdownRenderer.render(h.text, newText)
                        h.lastWasMarkdown = true
                        h.lastBoundTextLen = newText.length
                    }
                } else {
                    val oldLen = h.lastBoundTextLen
                    val curText = h.text.text.toString()
                    // ★ 首次 bind 判定：lastBoundTextLen=0 表示从未渲染过，必须走 Markdown
                    //   （修复：首次 bind 时 h.text.text=""，startsWith("")=true 会误判为增量跳过 Markdown）
                    val isFirstBind = oldLen == 0 && newText.isNotEmpty()
                    val isIncrementalAppend = !isFirstBind
                            && newText.length > oldLen
                            && newText.length >= curText.length
                            && newText.startsWith(curText)
                    if (isIncrementalAppend) {
                        h.text.text = newText
                        h.lastWasMarkdown = false
                        AppLog.i("ChatAdapter", "增量绑定(纯文本): ${newText.length}字")
                    } else {
                        MarkdownRenderer.render(h.text, newText)
                        h.lastWasMarkdown = true
                        AppLog.i("ChatAdapter", "Markdown 渲染(其它): ${newText.length}字")
                    }
                    h.lastBoundTextLen = newText.length
                }
                if (!rs.isNullOrBlank()) {
                    h.reasoningHeader.visibility = View.VISIBLE
                    h.reasoningHeader.text = if (h.expanded) h.itemView.context.getString(R.string.reasoning_hide) else h.itemView.context.getString(R.string.reasoning_toggle)
                    h.reasoningText.text = rs
                    h.reasoningText.visibility = if (h.expanded) View.VISIBLE else View.GONE
                } else {
                    h.reasoningHeader.visibility = View.GONE
                    h.reasoningText.visibility = View.GONE
                }
                // ★ 工具调用区渲染
                renderTools(h, m.toolCalls)
                // ★ TTS 播报按钮
                bindTtsButton(h, m)
                // ★ 时间小字
                renderTime(h, m, position)
            }
            is UserVH -> {
                MarkdownRenderer.render(h.text, m.text)
                renderTime(h, m, position)
            }
            is NoticeVH -> h.text.text = m.text
        }
    }

    /** ★ 同步后备列表：不依赖 AsyncListDiffer 的 currentList（异步），避免连续 submitList 时丢数据。 */
    private val backingList = mutableListOf<ChatMessage>()

    /** 追加一条消息（滚动由调用方处理）。 */
    fun add(msg: ChatMessage) {
        backingList.add(msg)
        submitList(ArrayList(backingList))
    }

    /** 替换或追加：若 [msg] 的 id 已存在则更新，否则追加。 */
    fun upsert(msg: ChatMessage) {
        val idx = backingList.indexOfFirst { it.id == msg.id }
        if (idx >= 0) backingList[idx] = msg else backingList.add(msg)
        submitList(ArrayList(backingList))
    }

    /** ★ 增量追加正文：不 submitList，只 notifyItemChanged(PayloadAppendText)，
     *    holder 做 TextView.append(delta) —— 增量布局，避免高频 setText 全量闪烁。 */
    fun appendLastAssistant(delta: String) {
        val idx = backingList.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
        if (idx >= 0) {
            backingList[idx] = backingList[idx].copy(text = backingList[idx].text + delta)
            notifyItemChanged(idx, PayloadAppendText(delta))
        }
    }

    /** ★ 思考结束开始正文时自动折叠思考区（正文给位，用户可点击再展开）。 */
    fun collapseLastReasoning() {
        val idx = backingList.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
        if (idx >= 0) notifyItemChanged(idx, PAYLOAD_COLLAPSE)
    }

    /** 更新最后一条助手消息（用于流式增量），没有则新增。 */
    fun updateLastAssistant(text: String) {
        val idx = backingList.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
        if (idx >= 0) {
            // ★ copy() 保留 id 和 reasoning（id 是主构造参数），DiffUtil 识别为同一 item → 原地更新不新增
            backingList[idx] = backingList[idx].copy(text = text)
        } else {
            backingList.add(ChatMessage.create(ChatMessage.Role.ASSISTANT, text))
        }
        submitList(ArrayList(backingList))
    }

    /** ★ 最终提交（用于 onAssistantComplete）：committed=true 强制下次重绑走 Markdown。
     *   依赖 ChatMessage.committed 参与 DIFF 内容比对：false→true 必触发重绑，
     *   即使流式累积文本 == trim 后 final（DiffUtil 判内容未变不重绑 →
     *   混合态不修复的随机 bug，见 ChatMessage.committed 注释）。 */
    fun commitLastAssistant(text: String) {
        val idx = backingList.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
        if (idx >= 0) {
            backingList[idx] = backingList[idx].copy(text = text, committed = true)
        } else {
            backingList.add(ChatMessage.create(ChatMessage.Role.ASSISTANT, text).copy(committed = true))
        }
        submitList(ArrayList(backingList))
    }

    /** ★ 设置最后一条助手消息的思考过程（完整覆盖，用于 reasoning 流式）。 */
    fun setLastReasoning(reasoning: String) {
        val idx = backingList.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
        if (idx >= 0) {
            // ★ 直接覆盖（flushReasoning 已累积完整内容），不再追加，避免重复
            backingList[idx] = backingList[idx].copy(reasoning = reasoning)
            submitList(ArrayList(backingList))
            notifyItemChanged(idx, PAYLOAD_REASONING)
        }
    }

    /** ★ 设置最后一条助手消息的工具调用列表（完整覆盖）。 */
    fun setLastToolCalls(toolCalls: List<ToolCallDisplay>) {
        val idx = backingList.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
        if (idx >= 0) {
            backingList[idx] = backingList[idx].copy(toolCalls = toolCalls)
            submitList(ArrayList(backingList))
            notifyItemChanged(idx, PAYLOAD_TOOLS)
        }
    }

    /** ★ 渲染工具调用区。格式（每行一个）：
     *  ```
     *  ⏳ [bash] python3 baidu-search.py ...
     *  ✓ [read] foo.json
     *  ✗ [bash] sleep 60  (error)
     *  ```
     */
    private fun renderTools(h: AssistantVH, tools: List<ToolCallDisplay>) {
        if (tools.isEmpty()) {
            h.toolsHeader.visibility = View.GONE
            h.toolsText.visibility = View.GONE
            return
        }
        h.toolsHeader.visibility = View.VISIBLE
        val ctx = h.itemView.context
        // ★ 有 running 状态时显示“正在调用”文案，全部结束显示“调用了”
        val hasRunning = tools.any { it.status == "running" }
        h.toolsHeader.text = when {
            hasRunning -> ctx.getString(R.string.tools_running_header).replace("N", tools.size.toString())
            tools.size == 1 -> ctx.getString(R.string.tools_toggle_one)
            else -> ctx.getString(R.string.tools_toggle).replace("N", tools.size.toString())
        }
        val sb = StringBuilder()
        for (tc in tools) {
            val icon = when (tc.status) {
                "done" -> ctx.getString(R.string.tools_done)
                "error" -> ctx.getString(R.string.tools_error)
                else -> ctx.getString(R.string.tools_running)  // running
            }
            sb.append(icon).append(' ').append('[').append(tc.name).append(']').append(' ').append(tc.argsPreview)
            if (tc.status == "error") sb.append("  (error)")
            sb.append('\n')
        }
        h.toolsText.text = sb.toString().trimEnd()
        h.toolsText.visibility = if (h.toolsExpanded) View.VISIBLE else View.GONE
    }

    fun clearAll() {
        backingList.clear()
        submitList(emptyList())
    }

    /** 一次性替换整个列表（避免 clearAll+逐个 add 的异步竞态叠加）。 */
    fun submitAll(list: List<ChatMessage>, onCommit: Runnable? = null) {
        backingList.clear()
        backingList.addAll(list)
        submitList(ArrayList(backingList), onCommit)
    }
}
