package com.shipyard.deepchat.net

import android.content.Context
import com.shipyard.deepchat.data.Provider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * DeepSeek 的语音工具接口（公开 API，按量计费）：
 *   POST /v1/tools/tts  {text, voice_id, format}  -> audio/wav 流
 *   POST /v1/tools/asr  multipart file            -> {text, duration_ms}
 */
data class ShareMsg(val role: String, val content: String, val reasoning: String)

data class SharePayload(val title: String, val messages: List<ShareMsg>)

/**
 * 读官方分享链接的内容：GET https://chat.deepseek.com/api/v0/share/content?share_id=xxx
 * 分享页是公开的，不需要登录。
 */
class DeepSeekTools(private val ctx: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun url(p: Provider, path: String): String {
        val b = p.baseUrl.trimEnd('/')
        return if (b.endsWith("/v1")) "$b/tools/$path" else "$b/v1/tools/$path"
    }

    /** 整段合成，返回本地 wav 文件；失败返回 null 和原因 */
    fun tts(p: Provider, text: String, voiceId: String): Pair<File?, String?> {
        if (p.apiKey.isBlank()) return null to "没填 API Key"
        if (text.isBlank()) return null to "没有可朗读的内容"
        val body = buildJsonObject {
            put("text", text.take(9000))
            put("voice_id", voiceId)
            put("format", "wav")
            put("filter_markdown", true)
        }.toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url(url(p, "tts"))
            .header("Authorization", "Bearer ${p.apiKey.trim()}")
            .post(body)
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val raw = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                    val msg = runCatching {
                        json.parseToJsonElement(raw).jsonObject["error"]?.jsonObject
                            ?.get("message")?.jsonPrimitive?.content
                    }.getOrNull()
                    return null to (msg ?: "HTTP ${resp.code}")
                }
                val bytes = resp.body?.bytes() ?: return null to "空响应"
                if (bytes.size < 200) return null to "音频为空"
                val f = File(ctx.cacheDir, "tts_${System.currentTimeMillis()}.wav")
                f.writeBytes(bytes)
                f to null
            }
        } catch (e: Exception) {
            null to (e.message ?: "网络错误")
        }
    }

    /** 读分享链接里的对话 */
    suspend fun fetchShare(shareId: String): Pair<SharePayload?, String?> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = "https://chat.deepseek.com/api/v0/share/content?share_id=$shareId"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .get()
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext null to "HTTP ${resp.code}"
                    val root = runCatching {
                        json.parseToJsonElement(raw).jsonObject
                    }.getOrNull() ?: return@withContext null to "返回不是 JSON"
                    val data = root["data"]?.jsonObject
                        ?: return@withContext null to "返回缺少 data"
                    val biz = data["biz_data"]?.jsonObject
                        ?: return@withContext null to (
                            data["biz_msg"]?.jsonPrimitive?.content ?: "分享不存在"
                            )
                    val title = biz["title"]?.jsonPrimitive?.content?.ifBlank { null }
                        ?: "导入的对话"
                    val arr = biz["messages"] as? kotlinx.serialization.json.JsonArray
                        ?: return@withContext null to "分享里没有消息"
                    val msgs = arr.mapNotNull { el ->
                        val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                        val role = o["role"]?.jsonPrimitive?.content.orEmpty()
                        val content = textOf(o)
                        val reasoning = (o["thinking_content"] ?: o["reasoning_content"] ?: o["reasoning"])
                            ?.let { r ->
                                if (r is kotlinx.serialization.json.JsonPrimitive) r.content else ""
                            }.orEmpty()
                        if (content.isBlank() && reasoning.isBlank()) null
                        else ShareMsg(role, content, reasoning)
                    }
                    if (msgs.isEmpty()) null to "没有解析到内容"
                    else SharePayload(title, msgs) to null
                }
            } catch (e: Exception) {
                null to (e.message ?: "网络错误")
            }
        }

    /** content 可能是字符串，也可能是片段数组 */
    private fun textOf(o: kotlinx.serialization.json.JsonObject): String {
        val c = o["content"]
        if (c is kotlinx.serialization.json.JsonPrimitive) return c.content
        if (c is kotlinx.serialization.json.JsonArray) {
            return c.joinToString("") { f ->
                when (f) {
                    is kotlinx.serialization.json.JsonPrimitive -> f.content
                    is kotlinx.serialization.json.JsonObject ->
                        f["content"]?.jsonPrimitive?.content
                            ?: f["text"]?.jsonPrimitive?.content ?: ""
                    else -> ""
                }
            }
        }
        return o["text"]?.jsonPrimitive?.content.orEmpty()
    }

    /** 发一个最小请求验证 Key / 地址 / 模型是否真的能用 */
    suspend fun testChat(p: Provider): Pair<String?, String?> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (p.apiKey.isBlank()) return@withContext null to "先填 API Key"
            val model = p.defaultModel.ifBlank { p.models.firstOrNull() ?: "" }
            if (model.isBlank()) return@withContext null to "先填模型名"
            val base = p.baseUrl.trimEnd('/')
            val candidates = buildList {
                add("$base/chat/completions")
                if (!base.endsWith("/v1")) add("$base/v1/chat/completions")
            }
            val body = buildJsonObject {
                put("model", model)
                put(
                    "messages",
                    kotlinx.serialization.json.buildJsonArray {
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", "hi")
                            }
                        )
                    }
                )
                put("max_tokens", 1)
                put("stream", false)
            }.toString().toRequestBody("application/json".toMediaType())

            var lastErr = "未知错误"
            for (url in candidates) {
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${p.apiKey.trim()}")
                    .post(body)
                    .build()
                try {
                    client.newCall(req).execute().use { resp ->
                        val raw = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            return@withContext "连接正常 · $model" to null
                        }
                        val msg = runCatching {
                            json.parseToJsonElement(raw).jsonObject["error"]?.jsonObject
                                ?.get("message")?.jsonPrimitive?.content
                        }.getOrNull()
                        lastErr = "HTTP ${resp.code} " + (msg ?: raw.take(120))
                    }
                } catch (e: Exception) {
                    lastErr = e.message ?: "网络错误"
                }
            }
            null to lastErr
        }

    /** 拉取服务商可用模型：/models 与 /v1/models 都试一遍 */
    suspend fun listModels(p: Provider): Pair<List<String>?, String?> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (p.apiKey.isBlank()) return@withContext null to "先填 API Key"
            val base = p.baseUrl.trimEnd('/').removeSuffix("/chat/completions")
            val candidates = buildList {
                add("$base/models")
                if (!base.endsWith("/v1")) add("$base/v1/models")
            }.distinct()

            var lastErr = "未知错误"
            for (url in candidates) {
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${p.apiKey.trim()}")
                    .get()
                    .build()
                try {
                    client.newCall(req).execute().use { resp ->
                        val raw = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            lastErr = runCatching {
                                json.parseToJsonElement(raw).jsonObject["error"]?.jsonObject
                                    ?.get("message")?.jsonPrimitive?.content
                            }.getOrNull() ?: "HTTP ${resp.code}"
                            return@use
                        }
                        val arr = runCatching {
                            json.parseToJsonElement(raw).jsonObject["data"]
                                as kotlinx.serialization.json.JsonArray
                        }.getOrNull() ?: return@use
                        val ids = arr.mapNotNull {
                            (it as? kotlinx.serialization.json.JsonObject)
                                ?.get("id")?.jsonPrimitive?.content
                        }.filter { it.isNotBlank() }
                        if (ids.isNotEmpty()) return@withContext ids.sorted() to null
                    }
                } catch (e: Exception) {
                    lastErr = e.message ?: "网络错误"
                }
            }
            null to lastErr
        }

    /** 上传 wav 转文字 */
    suspend fun asr(p: Provider, wav: File): Pair<String?, String?> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (p.apiKey.isBlank()) return@withContext null to "没填 API Key"
            val part = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", wav.name, wav.asRequestBody("audio/wav".toMediaType()))
                .build()
            val req = Request.Builder()
                .url(url(p, "asr"))
                .header("Authorization", "Bearer ${p.apiKey.trim()}")
                .post(part)
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val msg = runCatching {
                            json.parseToJsonElement(raw).jsonObject["error"]?.jsonObject
                                ?.get("message")?.jsonPrimitive?.content
                        }.getOrNull()
                        return@withContext null to (msg ?: "HTTP ${resp.code}")
                    }
                    val text = runCatching {
                        json.parseToJsonElement(raw).jsonObject["text"]?.jsonPrimitive?.content
                    }.getOrNull()
                    text to null
                }
            } catch (e: Exception) {
                null to (e.message ?: "网络错误")
            }
        }
}
