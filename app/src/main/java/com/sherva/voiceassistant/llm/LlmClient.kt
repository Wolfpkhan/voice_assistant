package com.sherva.voiceassistant.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容 Chat Completions 的 SSE 流式客户端。
 *
 * 适配 DeepSeek / 通义千问(DashScope 兼容模式) / 智谱 / OpenAI / Moonshot / 本地 vLLM 等
 * 任何遵循 OpenAI /v1/chat/completions 协议的服务——只需改 baseUrl + apiKey + model。
 *
 * 用法：
 *   client.chat(messages, onToken).collect { /* done = true */ }
 *   onToken 每收到一个增量 token 回调（用于即时喂给 TTS 分句播报）。
 */
class LlmClient(
    /** 例如 https://api.deepseek.com/v1 */
    var baseUrl: String,
    var apiKey: String,
    var model: String,
    /** 连接/读取超时（秒）。流式响应整体可能较长，读取超时设宽松。 */
    connectTimeoutSec: Long = 15,
    readTimeoutSec: Long = 120,
) {
    companion object { private const val TAG = "LlmClient" }

    data class Message(val role: String, val content: String) {
        companion object {
            fun system(text: String) = Message("system", text)
            fun user(text: String) = Message("user", text)
            fun assistant(text: String) = Message("assistant", text)
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
        .build()

    private var currentCall: Call? = null

    /**
     * 发起流式对话。
     * @param messages 上下文（含 system / 历史 / 最新 user）
     * @param onToken  每个增量【正文】片段回调（用于即时喂给 TTS 分句播报）
     * @param onReasoning 每个增量【思考】片段回调（reasoning 模型如 DeepSeek-V4-Flash 先思考后作答；不喂 TTS，仅用于 UI 提示）
     * @return 完整回复正文文本（Flow 完成时一次性给出，便于落库历史）
     *
     * 取消：collect 的协程被取消会触发 awaitClose → cancel 当前 HTTP 请求。
     */
    fun chat(
        messages: List<Message>,
        onToken: (String) -> Unit,
        onReasoning: ((String) -> Unit)? = null,
    ): Flow<String> = callbackFlow {
        require(baseUrl.isNotBlank()) { "baseUrl 未配置" }
        require(apiKey.isNotBlank()) { "apiKey 未配置" }

        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val body = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("temperature", 0.7)
            put("messages", JSONArray().apply {
                messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
            })
        }.toString()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val call = client.newCall(request)
        currentCall = call
        val full = StringBuilder()

        withContext(Dispatchers.IO) {
            try {
                val resp = call.execute()
                if (!resp.isSuccessful) {
                    val err = resp.body?.string()?.take(500) ?: ""
                    throw RuntimeException("LLM HTTP ${resp.code}: $err")
                }
                val source = resp.body?.source()
                    ?: throw RuntimeException("LLM 响应体为空")
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    // 解析增量 delta.content（正文）与 delta.reasoning_content（思考）
                    val (delta, reasoning) = parseDelta(data)
                    if (reasoning.isNotEmpty()) onReasoning?.invoke(reasoning)
                    if (delta.isNotEmpty()) {
                        full.append(delta)
                        onToken(delta)
                    }
                }
                resp.close()
            } catch (e: Throwable) {
                Log.e(TAG, "LLM 流式失败: ${e.message}", e)
                throw e
            } finally {
                currentCall = null
            }
        }
        trySend(full.toString())
        close()
        awaitClose { currentCall?.cancel() }
    }

    /** 解析 SSE 单行 JSON：返回 (正文 content, 思考 reasoning_content)。 */
    private fun parseDelta(json: String): Pair<String, String> {
        return try {
            val obj = JSONObject(json)
            val choices = obj.optJSONArray("choices") ?: return "" to ""
            if (choices.length() == 0) return "" to ""
            val choice = choices.optJSONObject(0) ?: return "" to ""
            val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: return "" to ""
            // ★ 注意：reasoning 模型（如 DeepSeek-V4-Flash）推理阶段 content 为 JSON null。
            //   Android 的 optString(key, fallback) 在值为 null 时返回字符串 "null"（非 fallback），
            //   必须先 isNull 判断，否则会把一堆 "null" 当正文拼入界面/语音。
            val content = if (delta.isNull("content")) "" else delta.optString("content", "")
            val reasoning = if (delta.isNull("reasoning_content")) "" else delta.optString("reasoning_content", "")
            content to reasoning
        } catch (_: Throwable) { "" to "" }
    }

    fun cancel() {
        currentCall?.cancel()
        currentCall = null
    }
}
