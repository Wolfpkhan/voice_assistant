package com.sherva.voiceassistant.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.MotionEvent
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.sherva.voiceassistant.R
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin

/**
 * Markdown 渲染工具（借鉴 hermes_chat_android 的 Markwon 方案）。
 *
 * 支持：粗体/斜体/标题/列表/表格/代码块/删除线/任务列表/链接。
 * 深色模式自适应：textColor 跟随主题。
 */
object MarkdownRenderer {

    @Volatile private var cached: Markwon? = null

    /** 获取（缓存）Markwon 实例。 */
    private fun get(context: Context): Markwon {
        cached?.let { return it }
        val m = Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TaskListPlugin.create(context))
            .build()
        cached = m
        return m
    }

    /** 在 TextView 上渲染 markdown，并自动检测纯文本链接。 */
    fun render(textView: TextView, markdown: String) {
        val ctx = textView.context
        // 主题文字色（用 ?attr/textColorPrimary，随深浅主题自动切换，避免资源 ID 坑）
        val ta = ctx.theme.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        val color = ta.getColor(0, 0xFF0D0D0D.toInt())
        ta.recycle()
        textView.setTextColor(color)

        get(ctx).setMarkdown(textView, flattenTableCells(markdown))
        // 纯文本 URL 自动识别（markdown 链接由 Markwon 处理）
        android.text.util.Linkify.addLinks(
            textView,
            android.text.util.Linkify.WEB_URLS or android.text.util.Linkify.EMAIL_ADDRESSES
        )
        // 链接点击：默认打开浏览器
        textView.movementMethod = linkMovementMethod(ctx)
    }

    /** ★ 表格 cell 内换行拍平：LLM 常在 cell 里输出真实换行（GPT 表格习惯），
     *    Markwon TableRowSpan 对多行 cell 绘制越界（内容溢出单元格/行高错乱）。
     *    处理：定位表格行（以 | 开头且含 | ），把行内除行尾外的 \n 替换为 "；"分隔，
     *    <br> 同样拍平（cell 内换行在窄列里用分隔号可读性更好）。
     *    非表格行不动。 */
    private fun flattenTableCells(markdown: String): String {
        if (!markdown.contains('|')) return markdown
        return markdown.split('\n').joinToString("\n") { line ->
            if (line.trimStart().startsWith("|") && line.count { it == '|' } >= 2) {
                // 表格行：内部换行已不可能（split 已按 \n 拆）——主要处理 <br>
                line.replace("<br>", "；").replace("<br/>", "；").replace("<br />", "；")
            } else {
                line
            }
        }.let { joined ->
            // 再处理真实 \n 混入 cell 的场景：某行以 | 开头但下一行以非 | 非空开头且上一行未闭合（| 数量为奇）
            // 简单可靠版：连续两个“以|开头”的行之间插入的“不以|开头且非空”的行，合并进上一行
            val lines = joined.split('\n').toMutableList()
            val out = mutableListOf<String>()
            var i = 0
            while (i < lines.size) {
                val cur = lines[i]
                val isTableRow = cur.trimStart().startsWith("|")
                if (isTableRow && i + 1 < lines.size) {
                    val nxt = lines[i + 1]
                    val nxtIsTableRow = nxt.trimStart().startsWith("|")
                    val nxtIsSep = nxt.trim().matches(Regex("\\|?[\\s:|-]+\\|?.*")) && nxt.contains('-')
                    // 中断行：不以 | 开头、非空、非分隔行 → 视为误入 cell 的换行内容
                    if (!nxtIsTableRow && nxt.isNotBlank() && !nxtIsSep) {
                        out.add(cur + "；" + nxt.trim())
                        i += 2
                        continue
                    }
                }
                out.add(cur)
                i++
            }
            out.joinToString("\n")
        }
    }

    private fun linkMovementMethod(ctx: Context): LinkMovementMethod = object : LinkMovementMethod() {
        override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) {
                val offset = getOffset(widget, event.x, event.y)
                if (offset >= 0) {
                    val spans = buffer.getSpans(offset, offset, URLSpan::class.java)
                    if (spans.isNotEmpty()) {
                        val url = spans[0].url
                        runCatching {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                        return true
                    }
                }
            }
            return super.onTouchEvent(widget, buffer, event)
        }
    }

    /** 由触摸坐标推算字符偏移（仿 hermes 实现）。 */
    private fun getOffset(textView: TextView, x: Float, y: Float): Int {
        val xLoc = x - textView.totalPaddingLeft + textView.scrollX
        val yLoc = y - textView.totalPaddingTop + textView.scrollY
        val layout = textView.layout ?: return -1
        val line = layout.getLineForVertical(yLoc.toInt())
        val left = layout.getLineLeft(line)
        val right = layout.getLineRight(line)
        return if (xLoc in left..right) layout.getOffsetForHorizontal(line, xLoc) else -1
    }
}
