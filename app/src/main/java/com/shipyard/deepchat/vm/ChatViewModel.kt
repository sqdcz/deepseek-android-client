package com.shipyard.deepchat.vm

import android.app.Application
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shipyard.deepchat.data.Citation
import com.shipyard.deepchat.data.Conversation
import com.shipyard.deepchat.data.Msg
import com.shipyard.deepchat.data.Provider
import com.shipyard.deepchat.data.Settings
import com.shipyard.deepchat.data.Store
import com.shipyard.deepchat.data.ToolTrace
import com.shipyard.deepchat.data.WebDav
import com.shipyard.deepchat.net.ApiClient
import com.shipyard.deepchat.net.Backup
import com.shipyard.deepchat.net.BackupCodec
import com.shipyard.deepchat.net.ChatEvent
import com.shipyard.deepchat.net.DeepSeekTools
import com.shipyard.deepchat.net.OfficialImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class UiState(
    val conversations: List<Conversation> = emptyList(),
    val current: Conversation? = null,
    val settings: Settings = Settings(),
    val streaming: Boolean = false,
    val streamingId: String? = null,
    val statusText: String = "",
    val message: String? = null,
    val syncState: String = "",
    val loaded: Boolean = false
) {
    /** 上下文 token 估算（当前会话）*/
    val contextTokens: Int
        get() = current?.messages?.sumOf {
            com.shipyard.deepchat.audio.Tokens.estimate(it.content) +
                com.shipyard.deepchat.audio.Tokens.estimate(it.reasoning)
        } ?: 0

    /** 今日用量 */
    val todayUsage: com.shipyard.deepchat.data.DayUsage
        get() = settings.dailyUsage[java.time.LocalDate.now().toString()]
            ?: com.shipyard.deepchat.data.DayUsage()
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val api = ApiClient()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var job: Job? = null

    private val ctx get() = getApplication<Application>()

    init {
        viewModelScope.launch {
            val s = Store.loadSettings(ctx)
            val list = Store.loadAll(ctx)
            val cur = list.firstOrNull() ?: Conversation(
                providerId = s.activeProvider.id,
                model = s.activeProvider.defaultModel,
                thinking = s.activeProvider.supportsThinking
            )
            _state.update {
                it.copy(
                    settings = s,
                    conversations = list,
                    current = cur,
                    loaded = true
                )
            }
            if (s.webdav.enabled && s.webdav.autoSync) syncNow(quiet = true)
        }
    }

    // ---------- 会话管理 ----------

    fun newChat() {
        stop()
        val s = _state.value.settings
        val p = s.activeProvider
        val conv = Conversation(
            providerId = p.id,
            model = p.defaultModel,
            thinking = p.supportsThinking,
            webSearch = false,
            effort = "high"
        )
        _state.update { st ->
            st.copy(
                current = conv,
                conversations = (listOf(conv) + st.conversations).sortedWith(order())
            )
        }
    }

    fun open(id: String) {
        stop()
        val c = _state.value.conversations.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(current = c) }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            Store.delete(ctx, id)
            _state.update { st ->
                val left = st.conversations.filterNot { it.id == id }
                st.copy(
                    conversations = left,
                    current = if (st.current?.id == id) left.firstOrNull() else st.current
                )
            }
        }
    }

    fun rename(id: String, title: String) {
        val c = _state.value.conversations.firstOrNull { it.id == id } ?: return
        commit(c.copy(title = title.trim().ifBlank { "新对话" }, updatedAt = System.currentTimeMillis()))
    }

    fun togglePin(id: String) {
        val c = _state.value.conversations.firstOrNull { it.id == id } ?: return
        commit(c.copy(pinned = !c.pinned))
    }

    fun clearAll() {
        viewModelScope.launch {
            _state.value.conversations.forEach { Store.delete(ctx, it.id) }
            _state.update { it.copy(conversations = emptyList(), current = null) }
        }
    }

    // ---------- 会话参数 ----------

    fun setModel(model: String) = mutateCurrent { it.copy(model = model) }
    fun setThinking(on: Boolean) = mutateCurrent { it.copy(thinking = on) }
    fun setWeb(on: Boolean) = mutateCurrent { it.copy(webSearch = on) }
    fun setEffort(e: String) = mutateCurrent { it.copy(effort = e) }
    fun setSystemPrompt(p: String) = mutateCurrent { it.copy(systemPrompt = p) }

    // ---------- 发送 ----------

    fun send(text: String, images: List<String> = emptyList()) {
        if (text.isBlank() && images.isEmpty()) return
        if (_state.value.streaming) return
        if (_state.value.current == null) newChat()
        val cur = _state.value.current ?: return
        val p = providerFor(cur)
        if (p.apiKey.isBlank()) {
            flash("${p.name} 还没填 API Key，去设置里补上")
            return
        }
        var conv = cur.copy(providerId = p.id, model = modelFor(cur, p))

        val um = Msg(role = "user", content = text.trim(), images = images)
        val am = Msg(role = "assistant", content = "")
        conv = conv.copy(
            messages = conv.messages + um + am,
            title = if (conv.messages.none { it.isUser }) titleOf(text, images) else conv.title,
            updatedAt = System.currentTimeMillis()
        )
        commit(conv)
        startStream(conv, am.id)
    }

    fun regenerate() {
        val cur = _state.value.current ?: return
        if (_state.value.streaming) return
        val msgs = cur.messages.toMutableList()
        while (msgs.isNotEmpty() && !msgs.last().isUser) msgs.removeAt(msgs.size - 1)
        if (msgs.isEmpty()) return
        val am = Msg(role = "assistant", content = "")
        val conv = cur.copy(messages = msgs + am, updatedAt = System.currentTimeMillis())
        commit(conv)
        startStream(conv, am.id)
    }

    fun editAndResend(msgId: String, newText: String) {
        val cur = _state.value.current ?: return
        val idx = cur.messages.indexOfFirst { it.id == msgId }
        if (idx < 0) return
        val kept = cur.messages.subList(0, idx).toMutableList()
        val edited = cur.messages[idx].copy(content = newText.trim())
        val am = Msg(role = "assistant", content = "")
        val conv = cur.copy(
            messages = kept + edited + am,
            updatedAt = System.currentTimeMillis()
        )
        commit(conv)
        startStream(conv, am.id)
    }

    fun stop() {
        job?.cancel()
        job = null
        val st = _state.value
        val conv = st.current ?: return
        val sid = st.streamingId ?: return
        val msgs = conv.messages.map {
            if (it.id == sid) it.copy(stopped = true, durationMs = it.durationMs) else it
        }
        commit(conv.copy(messages = msgs), )
        _state.update { it.copy(streaming = false, streamingId = null, statusText = "") }
    }

    // ---------- 系统提示词 / 记忆 / 用量 ----------

    /** 会话级 > 全局 > 服务商自带 */
    private fun systemFor(conv: Conversation, p: Provider): String {
        val s = _state.value.settings
        val base = conv.systemPrompt.ifBlank {
            s.globalSystemPrompt.ifBlank {
                p.systemPrompt.ifBlank { com.shipyard.deepchat.data.DEFAULT_SYSTEM_PROMPT }
            }
        }
        if (s.memories.isEmpty()) return base
        val mem = s.memories.filter { it.isNotBlank() }.joinToString("\n") { "- $it" }
        if (mem.isBlank()) return base
        val head = "以下是关于用户的长期信息，回答时请自然参考，不要主动复述："
        return (if (base.isBlank()) "" else base + "\n\n") + head + "\n" + mem
    }

    fun addMemory(text: String) {
        val t = text.trim()
        if (t.isBlank()) return
        updateSettings { it.copy(memories = (it.memories + t).distinct()) }
    }

    fun removeMemory(text: String) {
        updateSettings { it.copy(memories = it.memories - text) }
    }

    fun setGlobalSystemPrompt(text: String) {
        updateSettings { it.copy(globalSystemPrompt = text.trim()) }
    }

    /** 从服务商拉模型列表 */
    fun fetchModels(p: Provider, onResult: (List<String>?, String?) -> Unit) {
        viewModelScope.launch {
            val (list, err) = tools.listModels(p)
            onResult(list, err)
        }
    }

    /** 测试这个服务能不能真的发出请求 */
    fun testProvider(p: Provider, onResult: (String?, String?) -> Unit) {
        viewModelScope.launch {
            val (ok, err) = tools.testChat(p)
            onResult(ok, err)
        }
    }

    // ---------- 配置备份 ----------

    fun exportBackup(): String = BackupCodec.encode(
        Backup(settings = _state.value.settings)
    )

    fun importBackup(json: String, onDone: (String?) -> Unit) {
        val b = runCatching { BackupCodec.decode(json) }.getOrElse {
            onDone("解析失败：${it.message}")
            return
        }
        _state.update { it.copy(settings = b.settings) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { Store.saveSettings(ctx, b.settings) }
            val list = runCatching { Store.loadAll(ctx) }.getOrDefault(emptyList())
            _state.update { st ->
                st.copy(
                    conversations = list,
                    current = list.firstOrNull() ?: st.current
                )
            }
            onDone(null)
        }
    }

    /** 导入官方导出的数据包（zip 里的 conversations.json）*/
    fun importOfficial(zip: java.io.InputStream, onDone: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = OfficialImport.parse(zip) { i, t ->
                    _state.update { it.copy(statusText = "解析 $i / $t") }
                }
                var n = 0
                for ((i, c) in list.withIndex()) {
                    runCatching { Store.save(ctx, c) }.onSuccess { n++ }
                    if (i % 20 == 0) {
                        _state.update { it.copy(statusText = "保存 ${i + 1} / ${list.size}") }
                    }
                }
                val all = runCatching { Store.loadAll(ctx) }.getOrDefault(emptyList())
                _state.update {
                    it.copy(
                        conversations = all,
                        current = it.current ?: all.firstOrNull(),
                        statusText = ""
                    )
                }
                onDone(if (n == 0) "没解析到会话，确认是官方导出的 zip" else "已导入 $n 个会话")
            } catch (e: Exception) {
                _state.update { it.copy(statusText = "") }
                onDone("解析失败：${e.message}")
            } finally {
                runCatching { zip.close() }
            }
        }
    }

    /** 从官方分享链接导入一段对话 */
    fun importShareLink(url: String, onDone: (String?) -> Unit) {
        val text = url.trim()
        val id = Regex("""/share/([A-Za-z0-9_-]+)""").find(text)?.groupValues?.getOrNull(1)
            ?: Regex("""share_id=([A-Za-z0-9_-]+)""").find(text)?.groupValues?.getOrNull(1)
        if (id.isNullOrBlank()) {
            onDone("链接里找不到 share_id")
            return
        }
        viewModelScope.launch {
            val (payload, err) = tools.fetchShare(id)
            if (payload == null) {
                onDone(err ?: "导入失败")
                return@launch
            }
            val msgs = payload.messages.map { m ->
                Msg(
                    role = if (m.role.equals("USER", true) || m.role == "user") "user" else "assistant",
                    content = m.content,
                    reasoning = m.reasoning
                )
            }
            val conv = Conversation(
                title = payload.title.take(40),
                messages = msgs,
                providerId = _state.value.settings.activeProvider.id,
                model = _state.value.settings.activeProvider.defaultModel
            )
            withContext(Dispatchers.IO) { runCatching { Store.save(ctx, conv) } }
            val list = withContext(Dispatchers.IO) {
                runCatching { Store.loadAll(ctx) }.getOrDefault(emptyList())
            }
            _state.update { it.copy(conversations = list, current = conv) }
            onDone(null)
        }
    }

    private fun recordUsage() {
        val c = _state.value.current ?: return
        val lastUser = c.messages.lastOrNull { it.isUser }
        val lastAi = c.messages.lastOrNull { !it.isUser }
        val add = com.shipyard.deepchat.audio.Tokens.estimate(lastUser?.content ?: "") +
            com.shipyard.deepchat.audio.Tokens.estimate(lastAi?.content ?: "") +
            com.shipyard.deepchat.audio.Tokens.estimate(lastAi?.reasoning ?: "")
        if (add <= 0) return
        val key = java.time.LocalDate.now().toString()
        updateSettings { s ->
            val d = s.dailyUsage[key] ?: com.shipyard.deepchat.data.DayUsage()
            s.copy(
                dailyUsage = s.dailyUsage + (key to d.copy(tokens = d.tokens + add, calls = d.calls + 1))
            )
        }
    }

    private fun startStream(base0: Conversation, aiId: String) {
        val p = providerFor(base0)
        val base = base0.copy(providerId = p.id, model = modelFor(base0, p))
        val history = base.messages.filter { it.id != aiId }
        val startAt = System.currentTimeMillis()

        _state.update { it.copy(streaming = true, streamingId = aiId, statusText = "") }

        val convForApi = base.copy(systemPrompt = systemFor(base, p))

        job = viewModelScope.launch {
            var tokens = 0
            api.stream(ctx, p, convForApi, history).collect { ev ->
                when (ev) {
                    is ChatEvent.Text -> {
                        appendToMsg(aiId) { it.copy(content = it.content + ev.delta) }
                        tokens++
                        if (tokens % 40 == 0) persistCurrent()
                    }
                    is ChatEvent.Reasoning -> {
                        appendToMsg(aiId) { it.copy(reasoning = it.reasoning + ev.delta) }
                    }
                    is ChatEvent.ToolStart -> {
                        _state.update { s -> s.copy(statusText = ev.label) }
                        appendToMsg(aiId) { m ->
                            m.copy(tools = m.tools + ToolTrace(id = ev.id, kind = ev.kind, label = ev.label))
                        }
                    }
                    is ChatEvent.ToolDone -> {
                        _state.update { s -> s.copy(statusText = "") }
                        appendToMsg(aiId) { m ->
                            val tools = m.tools.map {
                                if (it.id == ev.id) it.copy(status = ev.status, sources = ev.sources) else it
                            }
                            m.copy(tools = tools, citations = mergeCitations(m.citations, ev.sources))
                        }
                    }
                    is ChatEvent.Raw -> {
                        appendToMsg(aiId) { m ->
                            val cites = parseAnnotations(ev.annotations)
                            m.copy(
                                rawTools = ev.tools.ifBlank { m.rawTools },
                                rawAnnotations = ev.annotations.ifBlank { m.rawAnnotations },
                                citations = mergeCitations(m.citations, cites)
                            )
                        }
                    }
                    is ChatEvent.Done -> {
                        appendToMsg(aiId) {
                            it.copy(durationMs = System.currentTimeMillis() - startAt)
                        }
                    }
                    is ChatEvent.Failed -> {
                        appendToMsg(aiId) {
                            it.copy(
                                error = ev.message,
                                durationMs = System.currentTimeMillis() - startAt
                            )
                        }
                    }
                }
            }
            _state.update { it.copy(streaming = false, streamingId = null, statusText = "") }
            persistCurrent()
            recordUsage()
            job = null
            val cfg = _state.value.settings.webdav
            if (cfg.enabled && cfg.autoSync) syncNow(quiet = true)
            if (_state.value.settings.autoSpeak) {
                val text = _state.value.current?.messages
                    ?.lastOrNull { !it.isUser && it.content.isNotBlank() }?.content
                if (!text.isNullOrBlank()) speakNow(text)
            }
        }
    }

    private fun parseAnnotations(raw: String): List<Citation> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            Store.json.parseToJsonElement(raw).let { el ->
                val arr = el as? kotlinx.serialization.json.JsonArray ?: return@let emptyList()
                arr.mapNotNull { e ->
                    val o = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    val uc = o["url_citation"] as? kotlinx.serialization.json.JsonObject
                        ?: return@mapNotNull null
                    val url = (uc["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
                    val title = (uc["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: url
                    Citation(title = title, url = url)
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun mergeCitations(old: List<Citation>, add: List<Citation>): List<Citation> {
        if (add.isEmpty()) return old
        val seen = old.map { it.url }.toMutableSet()
        val out = old.toMutableList()
        for (c in add) if (c.url.isNotBlank() && seen.add(c.url)) out.add(c)
        return out
    }

    // ---------- 设置 ----------

    fun updateSettings(block: (Settings) -> Settings) {
        val next = block(_state.value.settings)
        _state.update { it.copy(settings = next) }
        viewModelScope.launch(Dispatchers.IO) { Store.saveSettings(ctx, next) }
    }

    fun upsertProvider(p: Provider) = updateSettings { s ->
        val exists = s.providers.any { it.id == p.id }
        s.copy(
            providers = if (exists) s.providers.map { if (it.id == p.id) p else it }
            else s.providers + p
        )
    }

    fun deleteProvider(id: String) = updateSettings { s ->
        val left = s.providers.filterNot { it.id == id }
        if (left.isEmpty()) s
        else s.copy(
            providers = left,
            activeProviderId = if (s.activeProviderId == id) left.first().id else s.activeProviderId
        )
    }

    fun setActiveProvider(id: String) = updateSettings { it.copy(activeProviderId = id) }

    fun syncNow(quiet: Boolean = false) {
        val cfg = _state.value.settings.webdav
        if (!cfg.enabled || cfg.url.isBlank()) {
            if (!quiet) flash("先在设置里填 WebDAV 地址")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(syncState = "同步中…") }
            val (up, down) = WebDav.sync(ctx, cfg)
            val list = withContext(Dispatchers.IO) { Store.loadAll(ctx) }
            val curId = _state.value.current?.id
            _state.update {
                it.copy(
                    conversations = list,
                    current = list.firstOrNull { c -> c.id == curId } ?: it.current,
                    syncState = if (up == 0 && down == 0) "已是最新" else "↑$up ↓$down"
                )
            }
            updateSettings { s -> s.copy(webdav = s.webdav.copy(lastSync = System.currentTimeMillis())) }
            if (!quiet) flash("同步完成：上传 $up，下载 $down")
        }
    }

    // ---------- 朗读 / 语音 ----------

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var player: MediaPlayer? = null
    private val tools by lazy { DeepSeekTools(ctx) }

    fun toggleSpeak() {
        val on = !_state.value.settings.autoSpeak
        updateSettings { it.copy(autoSpeak = on) }
        if (!on) {
            runCatching { tts?.stop() }
            stopPlayer()
        }
    }

    /** 手动朗读一条内容 */
    fun speakNow(text: String) {
        if (text.isBlank()) return
        if (_state.value.settings.speakMode == "api") speakViaApi(text) else speakViaSystem(text)
    }

    private fun speakViaSystem(text: String) {
        if (tts == null) {
            initSystemTts { speakViaSystem(text) }
            return
        }
        if (!ttsReady) {
            flash("系统语音引擎还没就绪")
            return
        }
        runCatching {
            _state.value.settings.systemVoice.takeIf { it.isNotBlank() }?.let { name ->
                tts?.voices?.firstOrNull { it.name == name }?.let { tts?.voice = it }
            }
            tts?.speak(text.take(4000), TextToSpeech.QUEUE_FLUSH, null, "reply")
        }
    }

    private fun initSystemTts(after: (() -> Unit)? = null) {
        if (tts != null) return
        tts = TextToSpeech(ctx) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                runCatching { tts?.language = Locale.CHINA }
                after?.invoke()
            } else {
                flash("系统语音引擎不可用")
            }
        }
    }

    private fun speakViaApi(text: String) {
        val p = voiceProvider()
        if (p == null) {
            flash("API 朗读要用 DeepSeek 官方 Key")
            return
        }
        viewModelScope.launch {
            flash("正在合成语音…")
            val voice = _state.value.settings.voiceId
            val (file, err) = withContext(Dispatchers.IO) { tools.tts(p, text, voice) }
            if (file == null) {
                flash("朗读失败：${err ?: "未知错误"}")
                return@launch
            }
            playWav(file)
        }
    }

    private fun playWav(file: File) {
        runCatching {
            stopPlayer()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { mp ->
                    mp.release()
                    runCatching { file.delete() }
                }
                prepare()
                start()
            }
        }.onFailure { flash("播放失败：${it.message}") }
    }

    private fun stopPlayer() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    fun systemVoices(): List<Pair<String, String>> {
        if (tts == null) initSystemTts()
        return runCatching {
            tts?.voices
                ?.filter { it.locale.language == "zh" && !it.isNetworkConnectionRequired }
                ?.sortedBy { it.name }
                ?.map { it.name to it.name }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** 录音文件转文字，走 /v1/tools/asr */
    /** 下载并解压 Vosk 中文模型 */
    fun downloadVoskModel(onProgress: (Int) -> Unit, onDone: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val zip = File(ctx.cacheDir, com.shipyard.deepchat.audio.AsrModel.ZIP_NAME)
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val req = okhttp3.Request.Builder()
                    .url(com.shipyard.deepchat.audio.AsrModel.DOWNLOAD_URL)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        onDone("下载失败 HTTP ${resp.code}")
                        return@launch
                    }
                    val total = resp.body?.contentLength() ?: -1L
                    val input = resp.body?.byteStream() ?: run {
                        onDone("下载内容为空")
                        return@launch
                    }
                    var read = 0L
                    zip.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) onProgress((read * 100 / total).toInt())
                        }
                    }
                }
                onProgress(100)
                val err = com.shipyard.deepchat.audio.AsrModel.unzip(zip, ctx)
                runCatching { zip.delete() }
                onDone(err)
            } catch (e: Exception) {
                runCatching { zip.delete() }
                onDone(e.message ?: "下载出错")
            }
        }
    }

    /** 从 SAF 选的目录复制模型进来 */
    fun importVoskTree(tree: android.net.Uri, onDone: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, tree)
            if (root == null) {
                onDone("打不开这个目录")
                return@launch
            }
            val target = com.shipyard.deepchat.audio.AsrModel.dir(ctx)
            runCatching { target.deleteRecursively() }
            target.mkdirs()

            fun copy(dir: androidx.documentfile.provider.DocumentFile, out: File) {
                for (f in dir.listFiles()) {
                    if (f.isDirectory) {
                        val d = File(out, f.name ?: "dir")
                        d.mkdirs()
                        copy(f, d)
                    } else {
                        val n = f.name ?: continue
                        runCatching {
                            ctx.contentResolver.openInputStream(f.uri)?.use { ins ->
                                File(out, n).outputStream().use { ins.copyTo(it) }
                            }
                        }
                    }
                }
            }
            runCatching { copy(root, target) }
                .onFailure { onDone("复制失败：${it.message}"); return@launch }
            onDone(
                if (com.shipyard.deepchat.audio.AsrModel.ready(ctx)) null
                else "没找到 am/ 或 conf/，确认选的是模型根目录"
            )
        }
    }

    fun deleteVoskModel() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { com.shipyard.deepchat.audio.AsrModel.dir(ctx).deleteRecursively() }
            _state.update { it.copy(statusText = "") }
        }
    }

    /** 语音走 DeepSeek 官方通道，第三方中转没有 /tools/asr */
    private fun voiceProvider(): Provider? =
        _state.value.settings.providers.firstOrNull {
            it.kind == "deepseek" && it.apiKey.isNotBlank()
        }

    fun transcribe(wav: File, onDone: (String) -> Unit) {
        val p = voiceProvider()
        if (p == null) {
            runCatching { wav.delete() }
            flash("语音识别要用 DeepSeek 官方 Key，先去设置里填上")
            return
        }
        viewModelScope.launch {
            flash("正在识别语音…")
            val (text, err) = tools.asr(p, wav)
            runCatching { wav.delete() }
            if (text.isNullOrBlank()) flash("识别失败：${err ?: "没有听清"}")
            else onDone(text.trim())
        }
    }

    override fun onCleared() {
        runCatching { tts?.shutdown() }
        runCatching { player?.release() }
        tts = null
        player = null
        super.onCleared()
    }

    fun flash(msg: String) = _state.update { it.copy(message = msg) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    // ---------- 内部 ----------

    private fun order() = compareByDescending<Conversation> { it.pinned }
        .thenByDescending { it.updatedAt }

    /** 一律用当前选中的服务商，避免历史会话把 provider 锁在旧的那个上 */
    private fun providerFor(c: Conversation): Provider = _state.value.settings.activeProvider

    /** 会话里填了模型就用它，没填才落到服务商默认模型 */
    private fun modelFor(c: Conversation, p: Provider): String =
        c.model.takeIf { it.isNotBlank() }
            ?: p.defaultModel.ifBlank { p.models.firstOrNull() ?: "" }

    private fun titleOf(text: String, images: List<String>): String {
        val t = text.replace(Regex("\\s+"), " ").trim()
        return when {
            t.isNotBlank() -> t.take(24)
            images.isNotEmpty() -> "图片对话"
            else -> "新对话"
        }
    }

    private fun mutateCurrent(block: (Conversation) -> Conversation) {
        val c = _state.value.current ?: return
        commit(block(c))
    }

    private fun appendToMsg(id: String, block: (Msg) -> Msg) {
        _state.update { st ->
            val conv = st.current ?: return@update st
            val msgs = conv.messages.map { if (it.id == id) block(it) else it }
            val updated = conv.copy(messages = msgs)
            st.copy(
                current = updated,
                conversations = st.conversations.map { if (it.id == updated.id) updated else it }
            )
        }
    }

    private fun persistCurrent() {
        val conv = _state.value.current ?: return
        viewModelScope.launch(Dispatchers.IO) { Store.save(ctx, conv) }
    }

    private fun commit(conv: Conversation, persist: Boolean = true) {
        _state.update { st ->
            val list = (st.conversations.filterNot { it.id == conv.id } + conv).sortedWith(order())
            st.copy(current = conv, conversations = list)
        }
        if (persist) viewModelScope.launch(Dispatchers.IO) { Store.save(ctx, conv) }
    }
}
