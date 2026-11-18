package com.shipyard.deepchat.net

import com.shipyard.deepchat.data.Conversation
import com.shipyard.deepchat.data.Msg
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.time.OffsetDateTime
import java.util.zip.ZipInputStream

/**
 * 官方数据包（deepseek_data-YYYY-MM-DD.zip）里的 conversations.json：
 * [{id, title, inserted_at, updated_at, mapping: {key: {message: {fragments: [{type, content}]}}}}]
 *
 * 47MB 不能一次建成 DOM，这里按顶层大括号切段逐段解析。
 */
object OfficialImport {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private data class Item(val ts: Long, val msg: Msg)

    fun parse(zip: InputStream, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<Conversation> {
        var text: String? = null
        ZipInputStream(zip.buffered()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.name.endsWith("conversations.json", true)) {
                    text = zis.readBytes().toString(Charsets.UTF_8)
                    break
                }
            }
        }
        val raw = text ?: return emptyList()
        val chunks = splitTopArray(raw)
        val out = ArrayList<Conversation>(chunks.size)
        chunks.forEachIndexed { i, chunk ->
            runCatching { toConversation(json.parseToJsonElement(chunk).jsonObject) }
                .getOrNull()
                ?.let { out.add(it) }
            if (i % 8 == 0 || i == chunks.size - 1) onProgress(i + 1, chunks.size)
        }
        return out
    }

    /** 只切顶层对象，字符串里的括号与转义都跳过 */
    private fun splitTopArray(s: String): List<String> {
        val res = ArrayList<String>()
        var depth = 0
        var start = -1
        var inStr = false
        var esc = false
        for (i in s.indices) {
            val c = s[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        res.add(s.substring(start, i + 1))
                        start = -1
                    }
                }
            }
        }
        return res
    }

    private fun toConversation(o: JsonObject): Conversation? {
        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = o["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "未命名对话"
        val created = parseTime(o["inserted_at"]?.jsonPrimitive?.contentOrNull)
        val updated = parseTime(o["updated_at"]?.jsonPrimitive?.contentOrNull, created)
        val mapping = o["mapping"] as? JsonObject ?: return null

        val items = ArrayList<Item>()
        for ((_, v) in mapping) {
            val mo = v as? JsonObject ?: continue
            val m = mo["message"] as? JsonObject ?: continue
            val frags = m["fragments"] as? JsonArray ?: continue
            val ts = parseTime(m["inserted_at"]?.jsonPrimitive?.contentOrNull, created)

            val req = StringBuilder()
            val think = StringBuilder()
            val resp = StringBuilder()
            for (f in frags) {
                val fo = f as? JsonObject ?: continue
                val type = fo["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val content = (fo["content"] as? JsonPrimitive)?.content.orEmpty()
                if (content.isBlank()) continue
                when (type) {
                    "REQUEST" -> req.append(content)
                    "THINK" -> {
                        if (think.isNotEmpty()) think.append("\n\n")
                        think.append(content)
                    }
                    "RESPONSE" -> {
                        if (resp.isNotEmpty()) resp.append("\n\n")
                        resp.append(content)
                    }
                }
            }
            if (req.isNotEmpty()) {
                items.add(Item(ts, Msg(role = "user", content = req.toString(), createdAt = ts)))
            }
            if (resp.isNotEmpty() || think.isNotEmpty()) {
                items.add(
                    Item(
                        ts + 1,
                        Msg(
                            role = "assistant",
                            content = resp.toString(),
                            reasoning = think.toString(),
                            createdAt = ts + 1
                        )
                    )
                )
            }
        }
        if (items.isEmpty()) return null

        val msgs = items.sortedBy { it.ts }.map { it.msg }
        return Conversation(
            id = id,
            title = title.take(40),
            messages = msgs,
            createdAt = created,
            updatedAt = updated
        )
    }

    private fun parseTime(s: String?, fallback: Long = System.currentTimeMillis()): Long =
        runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrDefault(fallback)
}
