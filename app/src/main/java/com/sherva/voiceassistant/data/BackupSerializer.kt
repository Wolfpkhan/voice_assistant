package com.sherva.voiceassistant.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 备份数据结构（借鉴 hermes_chat_android 的 BackupData，用 org.json 实现）。
 */
object BackupSerializer {

    private const val VERSION = 1

    /** 序列化消息列表为备份 JSON 字符串。 */
    fun serialize(messages: List<MessageEntity>, appVersion: String): String {
        val msgs = JSONArray()
        messages.forEach { m ->
            msgs.put(
                JSONObject()
                    .put("content", m.content)
                    .put("timestamp", m.timestamp)
                    .put("isFromUser", m.isFromUser)
            )
        }
        val root = JSONObject()
            .put("version", VERSION)
            .put("appVersion", appVersion)
            .put("messageCount", messages.size)
            .put("messages", msgs)
        return root.toString(2)   // 2 = pretty print
    }

    /** 解析备份 JSON 字符串为消息列表。 */
    fun deserialize(json: String): List<MessageEntity> {
        val root = JSONObject(json)
        val version = root.optInt("version", 1)
        if (version > VERSION) throw IllegalArgumentException("备份版本 $version 不支持")
        val msgs = root.optJSONArray("messages") ?: return emptyList()
        val list = ArrayList<MessageEntity>(msgs.length())
        for (i in 0 until msgs.length()) {
            val o = msgs.getJSONObject(i)
            list.add(
                MessageEntity(
                    content = o.optString("content", ""),
                    timestamp = o.optLong("timestamp", System.currentTimeMillis()),
                    isFromUser = o.optBoolean("isFromUser", false),
                )
            )
        }
        return list
    }
}
