package com.shipyard.deepchat.net

import android.content.Context
import android.util.Base64
import com.shipyard.deepchat.data.Citation
import com.shipyard.deepchat.data.Conversation
import com.shipyard.deepchat.data.Msg
import com.shipyard.deepchat.data.Provider
import com.shipyard.deepchat.data.Store
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

sealed interface ChatEvent {
    data class Text(val delta: String) : ChatEvent
    data class Reasoning(val delta: String) : ChatEvent
    data class ToolStart(val id: String, val kind: String, val label: String) : ChatEvent
    data class ToolDone(val id: String, val status: String, val sources: List<Citation>) : ChatEvent
    data class Raw(val tools: String = "", val annotations: String = "") : ChatEvent
    data class Done(val finishReason: String?) : ChatEvent
    data class Failed(val message: String) : ChatEvent
}

class ApiClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun stream(
        ctx: Context,
        provider: Provider,
        conv: Conversation,
        history: List<Msg>
    ): Flow<ChatEvent> = flow {
        val payload = buildBody(ctx, provider, conv, history)
        val req = Request.Builder()
            .url(provider.chatUrl)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("Authorization", "Bearer ${provider.apiKey.trim()}")
            .apply { provider.extraHeaders.forEach { (k, v) -> header(k, v) } }
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val call = client.newCall(req)
        val job = currentCoroutineContext()[Job]
        job?.invokeOnCompletion { runCatching { call.cancel() } }

        var finish: String? = null
        var rawTools = ""
        var rawAnn = ""

        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    emit(ChatEvent.Failed(httpError(resp)))
                    return@use
                }
                val source = resp.body?.source()
                if (source == null) {
                    emit(ChatEvent.Failed("响应为空"))
                    return@use
                }
                while (currentCoroutineContext().isActive) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val el = runCatching { json.parseToJsonElement(data) }.getOrNull() ?: continue
                    val obj = el as? JsonObject ?: continue

                    (obj["error"] as? JsonObject)?.let { err ->
                        val msg = err["message"]?.jsonPrimitive?.contentOrNull
                        emit(ChatEvent.Failed(msg ?: err.toString()))
                        return@use
                    }

                    // 联网工具事件：顶层与 choice 内都可能出现
                    val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                    val ev = (obj["server_tool_event"] as? JsonObject)
                        ?: (choice?.get("server_tool_event") as? JsonObject)
                    if (ev != null) handleToolEvent(ev, this)

                    val delta = choice?.get("delta") as? JsonObject
                    if (delta != null) {
                        (delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull
                            ?.takeIf { it.isNotEmpty() }?.let { emit(ChatEvent.Reasoning(it)) }
                        (delta["content"] as? JsonPrimitive)?.contentOrNull
                            ?.takeIf { it.isNotEmpty() }?.let { emit(ChatEvent.Text(it)) }
                        (delta["annotations"] as? JsonArray)?.let {
                            rawAnn = it.toString()
                        }
                        (delta["server_tool_calls"] as? JsonArray)?.let {
                            rawTools = it.toString()
                        }
                    }
                    (choice?.get("finish_reason") as? JsonPrimitive)?.contentOrNull?.let { finish = it }
                }
                if (rawTools.isNotEmpty() || rawAnn.isNotEmpty()) {
                    emit(ChatEvent.Raw(rawTools, rawAnn))
                }
                emit(ChatEvent.Done(finish))
            }
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) {
                emit(ChatEvent.Failed(e.message ?: e.javaClass.simpleName))
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun handleToolEvent(ev: JsonObject, out: FlowCollector<ChatEvent>) {
        val type = (ev["type"] as? JsonPrimitive)?.contentOrNull ?: return
        val call = ev["call"] as? JsonObject ?: return
        val id = (call["id"] as? JsonPrimitive)?.contentOrNull ?: call.toString().hashCode().toString()
        val kind = (call["kind"] as? JsonPrimitive)?.contentOrNull ?: "search"
        when (type) {
            "call_started" -> {
                val action = call["action"] as? JsonObject
                val label = when (kind) {
                    "read_link", "read_url" -> {
                        val u = (action?.get("url") as? JsonPrimitive)?.contentOrNull ?: ""
                        "读取 " + u.replace(Regex("^https?://"), "").take(40)
                    }
                    else -> {
                        val q = (action?.get("queries") as? JsonArray)
                            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                            ?.joinToString(" / ")
                        "搜索 " + (q ?: "").take(40)
                    }
                }
                out.emit(ChatEvent.ToolStart(id, kind, label.ifBlank { "联网中" }))
            }
            "call_completed" -> {
                val status = (call["status"] as? JsonPrimitive)?.contentOrNull ?: "completed"
                val sources = (call["sources"] as? JsonArray)?.mapNotNull { it.toCitation() } ?: emptyList()
                val page = call["page"] as? JsonObject
                val extra = if (page != null) listOfNotNull(page.toCitation()) else emptyList()
                out.emit(ChatEvent.ToolDone(id, status, sources + extra))
            }
        }
    }

    private fun JsonElement.toCitation(): Citation? {
        val o = this as? JsonObject ?: return null
        val url = (o["url"] as? JsonPrimitive)?.contentOrNull ?: return null
        return Citation(
            title = (o["title"] as? JsonPrimitive)?.contentOrNull ?: url,
            url = url,
            snippet = (o["snippet"] as? JsonPrimitive)?.contentOrNull ?: "",
            site = (o["site_name"] as? JsonPrimitive)?.contentOrNull ?: ""
        )
    }

    private fun httpError(resp: Response): String {
        val raw = runCatching { resp.body?.string() }.getOrNull().orEmpty()
        val parsed = runCatching {
            val o = json.parseToJsonElement(raw).jsonObject
            (o["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                ?: (o["error"] as? JsonPrimitive)?.contentOrNull
                ?: o["message"]?.jsonPrimitive?.contentOrNull
                ?: o["detail"]?.jsonPrimitive?.contentOrNull
                ?: o["msg"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val hint = when (resp.code) {
            400 -> "请求被拒（多半是模型名或参数不对）"
            401 -> "API Key 无效，检查是否复制完整"
            402 -> "账户余额不足"
            403 -> "该 Key 没有访问这个模型的权限"
            404 -> "地址或模型不存在，检查 Base URL 与模型名"
            413 -> "请求内容过大"
            422 -> "参数格式不对"
            429 -> "请求太频繁，稍后再试"
            500, 502, 503, 504 -> "服务端繁忙或不可用"
            else -> null
        }
        val detail = parsed?.takeIf { it.isNotBlank() } ?: raw.trim().take(160)
        return when {
            hint != null && detail.isNotBlank() -> "$hint（HTTP ${resp.code}）· $detail"
            hint != null -> "$hint（HTTP ${resp.code}）"
            detail.isNotBlank() -> "HTTP ${resp.code} · $detail"
            else -> "HTTP ${resp.code} ${resp.message}".trim()
        }
    }

    // ---------------- 请求体 ----------------

    private fun buildBody(
        ctx: Context,
        provider: Provider,
        conv: Conversation,
        history: List<Msg>
    ): String {
        val useWeb = provider.isDeepSeek && provider.supportsWeb && conv.webSearch
        val thinking = conv.thinking
        val useToolsFlag = useWeb

        val body = buildJsonObject {
            put("model", conv.model.ifBlank { provider.defaultModel })
            put("stream", true)
            putJsonArray("messages") {
                val sys = conv.systemPrompt.ifBlank { provider.systemPrompt }
                if (sys.isNotBlank()) add(buildJsonObject {
                    put("role", "system"); put("content", sys)
                })
                for (m in history) {
                    if (m.isUser) {
                        if (m.images.isEmpty()) {
                            add(buildJsonObject {
                                put("role", "user"); put("content", m.content)
                            })
                        } else {
                            add(buildJsonObject {
                                put("role", "user")
                                putJsonArray("content") {
                                    add(buildJsonObject {
                                        put("type", "text"); put("text", m.content)
                                    })
                                    for (img in m.images) {
                                        val data = imageDataUrl(ctx, img) ?: continue
                                        add(buildJsonObject {
                                            put("type", "image_url")
                                            putJsonObject("image_url") { put("url", data) }
                                        })
                                    }
                                }
                            })
                        }
                    } else {
                        if (m.content.isBlank() && m.rawTools.isBlank()) continue
                        add(buildJsonObject {
                            put("role", "assistant")
                            put("content", m.content)
                            if (provider.isDeepSeek && m.reasoning.isNotBlank()) {
                                put("reasoning_content", m.reasoning)
                            }
                            if (provider.isDeepSeek && useToolsFlag && m.rawTools.isNotBlank()) {
                                put("server_tool_calls", json.parseToJsonElement(m.rawTools))
                            }
                            if (provider.isDeepSeek && useToolsFlag && m.rawAnnotations.isNotBlank()) {
                                put("annotations", json.parseToJsonElement(m.rawAnnotations))
                            }
                        })
                    }
                }
            }

            if (provider.isDeepSeek) {
                putJsonObject("thinking") {
                    put("type", if (thinking) "enabled" else "disabled")
                }
                if (thinking) put("reasoning_effort", conv.effort)
            } else if (provider.supportsThinking) {
                put("reasoning_effort", conv.effort)
            }

            if (useWeb) {
                putJsonArray("tools") {
                    add(buildJsonObject {
                        put("type", "deepseek_web_search")
                        put("name", "deepseek_web_search")
                    })
                    add(buildJsonObject {
                        put("type", "deepseek_read_url")
                        put("name", "deepseek_read_url")
                    })
                }
            }

            provider.maxTokens?.let { put("max_tokens", it) }
            provider.temperature?.let { if (!(provider.isDeepSeek && thinking)) put("temperature", it) }
        }
        return body.toString()
    }

    private fun imageDataUrl(ctx: Context, name: String): String? {
        val f = Store.imageFile(ctx, name)
        if (!f.exists()) return null
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return null
        val ext = f.extension.lowercase().ifBlank { "jpeg" }
        val mime = when (ext) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
        return "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
