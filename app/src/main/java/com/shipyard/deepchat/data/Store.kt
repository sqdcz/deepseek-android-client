package com.shipyard.deepchat.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 纯文件持久化：每个会话一个 json，图片单独存文件。
 * 这样 WebDAV 同步只需要搬 json 文件，也便于手动导出。
 */
object Store {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
        isLenient = true
    }

    private val io = Mutex()

    private fun convDir(ctx: Context) = File(ctx.filesDir, "conv").apply { mkdirs() }
    fun imageDir(ctx: Context) = File(ctx.filesDir, "images").apply { mkdirs() }
    fun exportDir(ctx: Context) = File(ctx.filesDir, "export").apply { mkdirs() }

    fun convFile(ctx: Context, id: String) = File(convDir(ctx), "$id.json")

    suspend fun loadAll(ctx: Context): List<Conversation> = withContext(Dispatchers.IO) {
        io.withLock {
            convDir(ctx).listFiles { f -> f.extension == "json" }
                ?.mapNotNull { f ->
                    runCatching { json.decodeFromString<Conversation>(f.readText()) }.getOrNull()
                }
                ?.sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.updatedAt })
                ?: emptyList()
        }
    }

    suspend fun save(ctx: Context, conv: Conversation) = withContext(Dispatchers.IO) {
        io.withLock {
            val f = convFile(ctx, conv.id)
            val tmp = File(f.parentFile, "${conv.id}.tmp")
            tmp.writeText(json.encodeToString(Conversation.serializer(), conv))
            if (f.exists()) f.delete()
            tmp.renameTo(f)
            Unit
        }
    }

    suspend fun delete(ctx: Context, id: String) = withContext(Dispatchers.IO) {
        io.withLock {
            convFile(ctx, id).delete()
            Unit
        }
    }

    suspend fun saveSettings(ctx: Context, s: Settings) = withContext(Dispatchers.IO) {
        io.withLock {
            val f = File(ctx.filesDir, "settings.json")
            f.writeText(json.encodeToString(Settings.serializer(), s))
            Unit
        }
    }

    suspend fun loadSettings(ctx: Context): Settings = withContext(Dispatchers.IO) {
        io.withLock {
            val f = File(ctx.filesDir, "settings.json")
            if (!f.exists()) Settings()
            else runCatching { json.decodeFromString<Settings>(f.readText()) }.getOrElse { Settings() }
        }
    }

    suspend fun saveImage(ctx: Context, bytes: ByteArray, ext: String = "jpg"): String =
        withContext(Dispatchers.IO) {
            val name = "${System.currentTimeMillis()}_${(0..999).random()}.$ext"
            File(imageDir(ctx), name).writeBytes(bytes)
            name
        }

    fun imageFile(ctx: Context, name: String): File = File(imageDir(ctx), name)

    /** 会话纯文本导出 */
    fun toMarkdown(conv: Conversation): String {
        val sb = StringBuilder()
        sb.append("# ").append(conv.title).append("\n\n")
        for (m in conv.messages) {
            if (m.isUser) {
                sb.append("## 我\n\n").append(m.content).append("\n\n")
            } else {
                sb.append("## DeepChat\n\n")
                if (m.reasoning.isNotBlank()) {
                    sb.append("> 思考过程\n\n")
                    m.reasoning.lineSequence().forEach { sb.append("> ").append(it).append("\n") }
                    sb.append("\n")
                }
                sb.append(m.content).append("\n\n")
                if (m.citations.isNotEmpty()) {
                    sb.append("参考来源：\n")
                    m.citations.forEachIndexed { i, c ->
                        sb.append("${i + 1}. [${c.title.ifBlank { c.url }}](${c.url})\n")
                    }
                    sb.append("\n")
                }
            }
        }
        return sb.toString()
    }
}
