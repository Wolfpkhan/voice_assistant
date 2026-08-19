package com.sherva.voiceassistant.tts

/**
 * TTS 播报文本清洗（语音链路与手动播报共用）。
 *
 * 规则：
 * - URL → 「链接」（不念协议域名路径）
 * - 换行 → 逗号
 * - 跳过 emoji surrogate
 * - 只保留字母/数字/空格/常用标点/数字场景符号（%、℃、元等）
 * - 其余（markdown 符号、括号、箭头等）全部滤除
 * - 压缩连续空格/标点
 */
object TtsTextCleaner {

    fun clean(text: String): String {
        var t = text.replace(Regex("""https?://\S+"""), "链接")
        val sb = StringBuilder(t.length)
        for (ch in t) {
            if (Character.isSurrogate(ch)) continue   // 跳过 emoji
            if (ch == '\n') { sb.append('，'); continue }
            if (ch.isLetter() || ch.isDigit()) { sb.append(ch); continue }
            if (ch == ' ' || ch == '\t') { sb.append(ch); continue }
            if (ch in "。，、！？,.!?") { sb.append(ch); continue }
            // 保留数字场景符号（供 digitsToChinese 正则匹配）
            // 含 - – 等范围连接符（如 20-21点）
            if (ch in ":.%°℃¥￥\$元点分-/–") { sb.append(ch); continue }
            // 其他全去掉（括号、冒号、箭头、markdown 等一切标点符号）
        }
        return sb.toString()
            .replace(Regex("[ \t]+"), " ")
            .replace(Regex("[，。]{2,}"), "。")
            .trim()
    }
}
