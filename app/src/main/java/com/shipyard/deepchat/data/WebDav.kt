package com.shipyard.deepchat.data

import android.content.Context
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * 极简 WebDAV 同步：只用 PUT / GET / PROPFIND / MKCOL。
 * 同步单位是一个会话一个 json，冲突时按 updatedAt 取新。
 */
class WebDav(private val cfg: WebDavConfig) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun base(): String = cfg.url.trimEnd('/')
    private fun dir(): String = (cfg.path.trim('/').ifBlank { "deepchat" })
    private fun dirUrl(): String = "${base()}/${dir()}"

    private fun req(url: String, method: String = "GET"): Request.Builder {
        val b = Request.Builder().url(url)
        if (cfg.user.isNotBlank()) b.header("Authorization", Credentials.basic(cfg.user, cfg.pass))
        return b.method(method, null)
    }

    private fun mkcolDirs() {
        runCatching {
            client.newCall(
                Request.Builder().url(dirUrl())
                    .header("Authorization", Credentials.basic(cfg.user, cfg.pass))
                    .method("MKCOL", null).build()
            ).execute().close()
        }
    }

    fun putText(name: String, text: String): Boolean = runCatching {
        mkcolDirs()
        val body = text.toRequestBody("application/json; charset=utf-8".toMediaType())
        client.newCall(
            Request.Builder().url("${dirUrl()}/$name")
                .header("Authorization", Credentials.basic(cfg.user, cfg.pass))
                .put(body).build()
        ).execute().use { it.isSuccessful }
    }.getOrElse { false }

    fun getText(name: String): String? = runCatching {
        client.newCall(req("${dirUrl()}/$name").build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        }
    }.getOrNull()

    /** 返回 文件名 -> 远端修改时间(epoch ms) */
    fun list(): Map<String, Long> = runCatching {
        mkcolDirs()
        val body = """
            <?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:getlastmodified/><d:getcontentlength/></d:prop></d:propfind>
        """.trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaType())
        val r = client.newCall(
            Request.Builder().url(dirUrl().trimEnd('/') + "/")
                .header("Authorization", Credentials.basic(cfg.user, cfg.pass))
                .header("Depth", "1")
                .method("PROPFIND", body).build()
        ).execute()
        val xml = r.body?.string() ?: ""
        r.close()
        parseList(xml)
    }.getOrElse { emptyMap() }

    private fun parseList(xml: String): Map<String, Long> {
        val out = mutableMapOf<String, Long>()
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT")
        val blocks = Regex("<[^:>]*:?response[^>]*>(.*?)</[^:>]*:?response>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.groupValues[1] }
        for (b in blocks) {
            val href = Regex("<[^:>]*:?href[^>]*>(.*?)</[^:>]*:?href>").find(b)?.groupValues?.get(1)
                ?: continue
            if (!href.endsWith(".json")) continue
            val name = href.substringAfterLast('/')
            val lm = Regex("<[^:>]*:?getlastmodified[^>]*>(.*?)</[^:>]*:?getlastmodified>")
                .find(b)?.groupValues?.get(1)
            val ts = runCatching { fmt.parse(lm?.trim() ?: "")?.time ?: 0L }.getOrDefault(0L)
            out[name] = ts
        }
        return out
    }

    fun delete(name: String): Boolean = runCatching {
        client.newCall(req("${dirUrl()}/$name", "DELETE").build()).execute().use { it.isSuccessful }
    }.getOrElse { false }

    companion object {
        /** 双向同步：本地与远端按 updatedAt 取新；返回 (上传数, 下载数) */
        suspend fun sync(ctx: Context, cfg: WebDavConfig): Pair<Int, Int> =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (!cfg.enabled || cfg.url.isBlank()) return@withContext 0 to 0
                val dav = WebDav(cfg)
                var up = 0
                var down = 0
                val remote = dav.list()
                val local = Store.loadAll(ctx).associateBy { it.id }

                // 上传播不上去的／更新的
                for ((id, conv) in local) {
                    val name = "$id.json"
                    val remoteTs = remote[name]
                    if (remoteTs == null || conv.updatedAt > remoteTs) {
                        if (dav.putText(name, Store.json.encodeToString(Conversation.serializer(), conv))) up++
                    }
                }
                // 下载远端更新的
                for ((name, ts) in remote) {
                    val id = name.removeSuffix(".json")
                    val loc = local[id]
                    if (loc == null || ts > loc.updatedAt) {
                        val text = dav.getText(name) ?: continue
                        runCatching {
                            val conv = Store.json.decodeFromString<Conversation>(text)
                            Store.save(ctx, conv)
                            down++
                        }
                    }
                }
                up to down
            }
    }
}

/** 图片与长图导出用的文件工具 */
object Files {
    fun exportPng(ctx: Context, name: String, bytes: ByteArray): File {
        val f = File(Store.exportDir(ctx), name)
        f.writeBytes(bytes)
        return f
    }
}
