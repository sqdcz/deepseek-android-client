package com.shipyard.deepchat.net

import com.shipyard.deepchat.data.Conversation
import com.shipyard.deepchat.data.Msg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import java.util.concurrent.TimeUnit

/**
 * DeepSeek 的 Anthropic 兼容接口：POST {base}/anthropic/v1/messages
 * 相比 chat/completions 多了服务端标题、safety_check、
 * 以及 content_block 结构的引用与思考签名。
 */
class AnthropicClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun stream(
        p: com.shipyard.deepchat.data.Provider,
        conv: Conversation,
        history: List<Msg>
    ): Flow<ChatEvent> = flow {
        val base = p.baseUrl.trimEnd('/')
        val url = if (base.endsWith("/anthropic/v1")) "$base/messages"
        else if (base.endsWith("/anthropic")) "$base/v1/messages"
        else "$base/anthropic/v1/messages"

        // 首轮（只有一条 user、还没有 assistant 正文）才让服务端生成标题
        val needTitle = history.count { it.isUser } == 1 &&
            history.none { !it.isUser && it.content.isNotBlank() }
        val body = buildBody(conv, history, conv.systemPrompt, needTitle)

        val req = Request.Builder()
            .url(url)
            .header("x-api-key", p.apiKey.trim())
            .header("Authorization", "Bearer ${p.apiKey.trim()}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val resp = try {
            client.newCall(req).execute()
        } catch (e: Exception) {
            emit(ChatEvent.Failed(e.message ?: "网络错误"))
            return@flow
        }
        if (!resp.isSuccessful) {
            emit(ChatEvent.Failed(httpError(resp)))
            return@flow
        }
        val source = resp.body?.source()
        if (source == null) {
            emit(ChatEvent.Failed("响应为空"))
            return@flow
        }

        val toolNames = HashMap<String, String>()   // block id -> kind
        try {
            var eventName = ""
            val data = StringBuilder()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                    line.isEmpty() -> {
                        if (data.isNotEmpty()) {
                            emitEvents(eventName, data.toString(), toolNames)
                        }
                        eventName = ""
                        data.clear()
                    }
                }
            }
            if (data.isNotEmpty()) emitEvents(eventName, data.toString(), toolNames)
        } catch (e: Exception) {
            emit(ChatEvent.Failed(e.message ?: "连接中断"))
        } finally {
            runCatching { source.close() }
            runCatching { resp.close() }
        }
    }.flowOn(Dispatchers.IO)

    /** 把一条 SSE 事件翻译成若干 ChatEvent */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<ChatEvent>.emitEvents(
        event: String,
        raw: String,
        toolNames: MutableMap<String, String>
    ) {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val type = root["type"]?.jsonPrimitive?.contentOrNull ?: event
        when (type) {
            "content_block_start" -> {
                val block = root["content_block"]?.jsonObject ?: return
                when (block["type"]?.jsonPrimitive?.contentOrNull) {
                    "server_tool_use" -> {
                        val id = block["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val kind = block["kind"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        toolNames[id] = kind
                        emit(
                            ChatEvent.ToolStart(
                                id = id,
                                kind = kind,
                                label = labelOf(kind)
                            )
                        )
                    }

                    "web_search_tool_result" -> {
                        val id = block["tool_use_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val kind = block["kind"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val sources = parseSearchResults(block)
                        emit(ChatEvent.ToolDone(id, "ok", sources))
                    }

                    "web_fetch_tool_result" -> {
                        val id = block["tool_use_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val c = block["content"]?.jsonObject
                        val ok = c != null && c["type"]?.jsonPrimitive?.contentOrNull == "web_fetch_result"
                        val url = c?.get("url")?.jsonPrimitive?.contentOrNull
                        emit(
                            ChatEvent.ToolDone(
                                id,
                                if (ok) "ok" else "error",
                                if (ok && url != null) listOf(
                                    com.shipyard.deepchat.data.Citation(url = url)
                                ) else emptyList()
                            )
                        )
                    }
                }
            }

            "content_block_delta" -> {
                val delta = root["delta"]?.jsonObject ?: return
                when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                    "text_delta" -> {
                        val t = delta["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (t.isNotEmpty()) emit(ChatEvent.Text(t))
                    }

                    "thinking_delta" -> {
                        val t = delta["thinking"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (t.isNotEmpty()) emit(ChatEvent.Reasoning(t))
                    }

                    "citations_delta" -> {
                        val c = delta["citation"]?.jsonObject ?: return
                        val url = c["url"]?.jsonPrimitive?.contentOrNull ?: return
                        emit(
                            ChatEvent.Cite(
                                url = url,
                                title = c["title"]?.jsonPrimitive?.contentOrNull
                            )
                        )
                    }
                }
            }

            "message_delta" -> {
                val delta = root["delta"]?.jsonObject
                val title = delta?.get("title")?.jsonPrimitive?.contentOrNull
                if (!title.isNullOrBlank()) emit(ChatEvent.Title(title))
                val stop = delta?.get("stop_reason")?.jsonPrimitive?.contentOrNull
                if (stop == "refusal") emit(ChatEvent.Text("\n\n（这条内容被安全策略拦下了）"))
            }

            "message_stop" -> emit(ChatEvent.Done("end_turn"))
            "error" -> {
                val msg = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: raw.take(160)
                emit(ChatEvent.Failed(msg))
            }
        }
    }

    private fun parseSearchResults(block: JsonObject): List<com.shipyard.deepchat.data.Citation> {
        val content = block["content"]?.jsonArray ?: return emptyList()
        val out = ArrayList<com.shipyard.deepchat.data.Citation>()
        for (el in content) {
            val o = el as? JsonObject ?: continue
            if (o["type"]?.jsonPrimitive?.contentOrNull != "web_search_result") continue
            val url = o["url"]?.jsonPrimitive?.contentOrNull ?: continue
            out.add(
                com.shipyard.deepchat.data.Citation(
                    url = url,
                    title = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    site = o["site_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    snippet = o["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty()
                )
            )
        }
        return out
    }

    private fun labelOf(kind: String): String = when (kind) {
        "search", "deep_search" -> "正在搜索"
        "image_search", "deep_image_search" -> "正在搜图"
        "read_link" -> "正在阅读链接"
        else -> "正在联网"
    }

    private fun buildBody(
        conv: Conversation,
        history: List<Msg>,
        system: String,
        needTitle: Boolean
    ): String = buildJsonObject {
        put("model", conv.model.ifBlank { "deepseek-flash" })
        put("system", system)
        putJsonArray("messages") {
            for (m in history) {
                if (m.isUser) {
                    if (m.images.isEmpty()) {
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", m.content)
                        })
                    } else {
                        add(buildJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                if (m.content.isNotBlank()) {
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", m.content)
                                    })
                                }
                                for (img in m.images) {
                                    val (media, data) = readImage(img) ?: continue
                                    add(buildJsonObject {
                                        put("type", "image")
                                        putJsonObject("source") {
                                            put("type", "base64")
                                            put("media_type", media)
                                            put("data", data)
                                        }
                                    })
                                }
                            }
                        })
                    }
                } else {
                    if (m.content.isBlank()) continue
                    // 历史里不回传 thinking 块：Anthropic 要求签名，未保存时会被拒
                    add(buildJsonObject {
                        put("role", "assistant")
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", m.content)
                            })
                        }
                    })
                }
            }
        }
        putJsonObject("thinking") {
            put("type", if (conv.thinking) "enabled" else "disabled")
        }
        if (conv.webSearch) {
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
        putJsonObject("safety_check") { put("level", "high") }
        if (needTitle) putJsonObject("title") { put("enabled", true) }
        put("stream", true)
    }.toString()

    /** 读本地图片为 base64 + media type */
    private fun readImage(name: String): Pair<String, String>? {
        return runCatching {
            val f = com.shipyard.deepchat.data.Store.imageFile(ctxHolder, name)
            if (!f.exists()) return@runCatching null
            val media = if (name.endsWith(".png", true)) "image/png" else "image/jpeg"
            media to android.util.Base64.encodeToString(
                f.readBytes(),
                android.util.Base64.NO_WRAP
            )
        }.getOrNull()
    }

    private lateinit var ctxHolder: android.content.Context

    fun bind(ctx: android.content.Context): AnthropicClient {
        ctxHolder = ctx.applicationContext
        return this
    }

    private fun httpError(resp: okhttp3.Response): String {
        val raw = runCatching { resp.body?.string() }.getOrNull().orEmpty()
        val parsed = runCatching {
            json.parseToJsonElement(raw).jsonObject["error"]
                ?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val hint = when (resp.code) {
            400 -> "请求被拒（模型名或参数不对）"
            401 -> "API Key 无效"
            402 -> "余额不足"
            404 -> "地址或模型不存在"
            429 -> "请求太频繁"
            500, 502, 503, 504 -> "服务端繁忙"
            else -> null
        }
        val detail = parsed?.takeIf { it.isNotBlank() } ?: raw.trim().take(160)
        return when {
            hint != null && detail.isNotBlank() -> "$hint（HTTP ${resp.code}）· $detail"
            hint != null -> "$hint（HTTP ${resp.code}）"
            detail.isNotBlank() -> "HTTP ${resp.code} · $detail"
            else -> "HTTP ${resp.code}"
        }
    }
}
