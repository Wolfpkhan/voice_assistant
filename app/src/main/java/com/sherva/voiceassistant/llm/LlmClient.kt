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
 * 适配 DeepSeek / 通义千问(DashScope 兼容模式) / 智谱 / OpenAI / Moonshot / 本地 vLLM / pi-proxy 等
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
    // ★ 不设 readTimeout：等用户主动 abort 即可。设了反而误导——agent 在跑工具/sleep 重试时会被截断（之前 120s readTimeout + DeepSeek 调百度 429 重试 sleep 120+180 = 5 分钟，超过后客户端误报"timeout"但 agent 其实还在干活）。OkHttp readTimeout=0 表示无限等。
    readTimeoutSec: Long = 0,
) {
    companion object { private const val TAG = "LlmClient" }

    data class Message(val role: String, val content: String) {
        companion object {
            fun system(text: String) = Message("system", text)
            fun user(text: String) = Message("user", text)
            fun assistant(text: String) = Message("assistant", text)
        }
    }

    /** ★ 工具调用增量（OpenAI delta.tool_calls 单帧解析结果）。
     *  name 仅首帧有值；argsDelta 仅 args 增量帧有值；id 仅首帧有值。
     */
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argsDelta: String,
    )

    /** ★ 工具执行结束（来自 pi-proxy 的 SSE 注释行 [tool_end]）。
     *  callId 与 ToolCallDelta.id 对应。
     */
    data class ToolExecEnd(
        val callId: String,
        val toolName: String,
        val isError: Boolean,
    )

    /** ★ token 使用统计（来自 finish 帧的 usage 字段）。
     */
    data class UsageInfo(
        val promptTokens: Int,
        val completionTokens: Int,
        val totalTokens: Int,
    )

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
     * @param onToolCallDelta  工具调用增量（OpenAI delta.tool_calls）
     * @param onToolExecEnd    工具执行结束（pi-proxy SSE 注释行）
     * @param onFinish         流结束（含 finish_reason + usage）
     * @return 完整回复正文文本（Flow 完成时一次性给出，便于落库历史）
     *
     * 取消：collect 的协程被取消会触发 awaitClose → cancel 当前 HTTP 请求。
     */
    fun chat(
        messages: List<Message>,
        onToken: (String) -> Unit,
        onReasoning: ((String) -> Unit)? = null,
        onToolCallDelta: ((ToolCallDelta) -> Unit)? = null,
        onToolExecEnd: ((ToolExecEnd) -> Unit)? = null,
        onFinish: ((reason: String, usage: UsageInfo?) -> Unit)? = null,
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
        // ★ 流结束时的 finish_reason（默认 "stop"）。agent 流可能在中间发 "tool_calls"（继续调工具），最终才是 "stop"。
        var finalFinishReason = "stop"
        var finalUsage: UsageInfo? = null

        withContext(Dispatchers.IO) {
            try {
                val resp = call.execute()
                if (!resp.isSuccessful) {
                    val err = resp.body?.string()?.take(500) ?: ""
                    throw RuntimeException("LLM HTTP ${resp.code}: $err")
                }
                val source = resp.body?.source()
                    ?: throw RuntimeException("LLM 响应体为空")
                // ★ 是否收到过 [DONE] 哨兵；EOF 但未收到 → 流被截断（上游掐流 / 网络中断）
                var sawDone = false
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    // ★ SSE 注释行（以 ":" 开头，标准 OpenAI 客户端忽略）
                    //   pi-proxy 用此传递 tool_execution_start / tool_execution_end
                    if (line.startsWith(":")) {
                        parseSseComment(line)?.let { end ->
                            Log.i(TAG, "tool_exec_end: ${end.toolName} id=${end.callId} err=${end.isError}")
                            onToolExecEnd?.invoke(end)
                        }
                        continue
                    }
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") { sawDone = true; break }
                    // 解析单帧
                    parseFrame(data,
                        onContent = { delta ->
                            full.append(delta)
                            Log.d(TAG, "onToken delta=${delta.length}字: \"${delta.take(20)}...\"")
                            onToken(delta)
                        },
                        onReasoningDelta = { reasoning ->
                            Log.d(TAG, "onReasoning delta=${reasoning.length}字: \"${reasoning.take(20)}...\"")
                            onReasoning?.invoke(reasoning)
                        },
                        onToolCallDelta = { tcd ->
                            Log.d(TAG, "tool_call_delta idx=${tcd.index} name=${tcd.name} argsLen=${tcd.argsDelta.length}")
                            onToolCallDelta?.invoke(tcd)
                        },
                        onFinish = { reason, usage ->
                            finalFinishReason = reason
                            if (usage != null) finalUsage = usage  // ★ 保存最后一次的 usage
                            Log.i(TAG, "finish: reason=$reason, usage=$usage")
                            // ★ 不立刻 invoke onFinish，因为 agent 流可能在中间发 finish_reason=tool_calls
                            //   等到 [DONE] 或 EOF 时再 invoke 最终值
                        },
                    )
                }
                resp.close()
                // ★ 流真正结束（[DONE] 或 EOF）→ 一次性发出最终 finish_reason + usage
                onFinish?.invoke(finalFinishReason, finalUsage)
                // ★ 截断检测：EOF 但未收到 [DONE] 视为上游异常中断
                if (!sawDone) {
                    val msg = "LLM 流被截断（未收到 [DONE]，已收到 ${full.length} 字）"
                    Log.w(TAG, msg)
                    throw RuntimeException(msg)
                }
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

    /** 解析 pi-proxy SSE 注释行：返回 ToolExecEnd（仅 [tool_end] 行）或 null。
     *  格式：": [tool_start] bash id=call_xxx" 或 ": [tool_end] bash id=call_xxx status=ok"
     */
    private fun parseSseComment(line: String): ToolExecEnd? {
        // ": [tool_end] bash id=call_xxx status=ok"
        val m = Regex(":\\s*\\[tool_end\\]\\s+(\\S+)\\s+id=(\\S+)\\s+status=(\\S+)").find(line) ?: return null
        val (name, id, status) = m.destructured
        return ToolExecEnd(callId = id, toolName = name, isError = status == "error")
    }

    /** 解析单帧 JSON：分别回调四种增量。 */
    private fun parseFrame(
        json: String,
        onContent: (String) -> Unit,
        onReasoningDelta: (String) -> Unit,
        onToolCallDelta: (ToolCallDelta) -> Unit,
        onFinish: (reason: String, usage: UsageInfo?) -> Unit,
    ) {
        try {
            val obj = JSONObject(json)
            // ★ usage 字段（顶层，在 choices 之外）
            val usageObj = obj.optJSONObject("usage")
            val usage = if (usageObj != null) {
                UsageInfo(
                    promptTokens = usageObj.optInt("prompt_tokens", 0),
                    completionTokens = usageObj.optInt("completion_tokens", 0),
                    totalTokens = usageObj.optInt("total_tokens", 0),
                )
            } else null
            val choices = obj.optJSONArray("choices") ?: return
            if (choices.length() == 0) return
            val choice = choices.optJSONObject(0) ?: return

            // ★ finish_reason 可能在 choice 顶层
            val finishReason = choice.optString("finish_reason", "")
            if (finishReason.isNotEmpty() && finishReason != "null") {
                onFinish(finishReason, usage)
                // 注意：finish_reason 帧的 delta 可能为空，但仍可能有 tool_calls / content（少见）
            }

            val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: return

            // ★ delta.tool_calls（数组，可能多个，按 index 累积）
            val toolCalls = delta.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                for (i in 0 until toolCalls.length()) {
                    val tc = toolCalls.optJSONObject(i) ?: continue
                    val index = tc.optInt("index", 0)
                    val id = tc.optString("id", "").takeIf { it.isNotEmpty() && it != "null" }
                    val func = tc.optJSONObject("function")
                    val name = func?.optString("name", "")?.takeIf { it.isNotEmpty() && it != "null" }
                    val argsDelta = func?.optString("arguments", "") ?: ""
                    onToolCallDelta(ToolCallDelta(index = index, id = id, name = name, argsDelta = argsDelta))
                }
            }

            // ★ delta.content（正文）+ delta.reasoning_content（思考）
            val contentRaw = if (delta.isNull("content")) "" else delta.optString("content", "")
            val reasoningRaw = if (delta.isNull("reasoning_content")) "" else delta.optString("reasoning_content", "")

            if (reasoningRaw.isNotEmpty()) {
                // OpenAI 标准（独立字段）
                onReasoningDelta(reasoningRaw)
            } else if (contentRaw.isNotEmpty()) {
                // 走 Anthropic 风格状态机（可能含 <think>...</think>）
                val (content, reasoning) = splitAnthropicThink(contentRaw)
                if (reasoning.isNotEmpty()) onReasoningDelta(reasoning)
                if (content.isNotEmpty()) onContent(content)
            }
        } catch (_: Throwable) {
            // 忽略解析失败（流式协议偶发非 JSON 行）
        }
    }

    /** 拆分 Anthropic 风格 <think>...</think>（支持跨帧累积）。
     *  返回 (content, reasoning)。inThink/pendingThink 状态保存在实例字段。
     */
    private fun splitAnthropicThink(content: String): Pair<String, String> {
        val start = content.indexOf("<think>")
        val end = content.indexOf("</think>")
        return when {
            start >= 0 && end > start -> {
                val r = content.substring(start + 7, end)
                val c = content.substring(0, start) + content.substring(end + 8)
                inThink = false
                pendingThink = ""
                c to r
            }
            start >= 0 -> {
                pendingThink += content.substring(start + 7)
                inThink = true
                "" to ""
            }
            end >= 0 -> {
                val r = pendingThink + content.substring(0, end)
                val c = content.substring(end + 8)
                inThink = false
                pendingThink = ""
                c to r
            }
            inThink -> {
                pendingThink += content
                "" to ""
            }
            else -> content to ""
        }
    }

    // ★ 跨 SSE 增量帧的 think 块状态（Anthropic 风格 MiniMax-M3）
    private var inThink = false
    private var pendingThink = ""

    fun cancel() {
        currentCall?.cancel()
        currentCall = null
        // ★ 通知 pi-proxy 中断 pi 端正在运行的 agent run
        //   避免 Agent is already processing 错误导致后续请求被拒绝
        Thread {
            try {
                val abortUrl = baseUrl.trimEnd('/').removeSuffix("/v1") + "/v1/abort"
                val req = Request.Builder()
                    .url(abortUrl)
                    .post("".toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().close()
                Log.i(TAG, "已发送 abort 到 pi-proxy")
            } catch (e: Throwable) {
                Log.w(TAG, "abort pi-proxy 失败（可能是 pi-proxy 未运行）: ${e.message}")
            }
        }.apply { name = "llm-abort" }.start()
    }
}