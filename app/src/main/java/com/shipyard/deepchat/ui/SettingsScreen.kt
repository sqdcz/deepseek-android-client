package com.shipyard.deepchat.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shipyard.deepchat.R
import com.shipyard.deepchat.data.Provider
import com.shipyard.deepchat.data.Store
import com.shipyard.deepchat.data.WebDavConfig
import com.shipyard.deepchat.vm.ChatViewModel
import com.shipyard.deepchat.ui.theme.isDarkTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    val s = st.settings
    val cs = MaterialTheme.colorScheme
    val pageBg = if (isDarkTheme()) cs.background else cs.surfaceVariant
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<Provider?>(null) }
    var adding by remember { mutableStateOf(false) }
    var showAppearance by remember { mutableStateOf(false) }
    var showFont by remember { mutableStateOf(false) }
    var showVoice by remember { mutableStateOf(false) }
    var showPrompt by remember { mutableStateOf(false) }
    var showMemory by remember { mutableStateOf(false) }
    var showLink by remember { mutableStateOf(false) }
    var showAsr by remember { mutableStateOf(false) }
    var showVosk by remember { mutableStateOf(false) }

    val voskTree = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) vm.importVoskTree(uri) { err -> vm.flash(err ?: "模型已导入") }
    }

    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use {
                    it.write(vm.exportBackup().toByteArray())
                }
            }.onSuccess { vm.flash("配置已导出") }
                .onFailure { vm.flash("导出失败：${it.message}") }
        }
    }

    val zipImporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val ins = runCatching { ctx.contentResolver.openInputStream(uri) }.getOrNull()
            if (ins == null) vm.flash("打开文件失败")
            else vm.importOfficial(ins) { msg -> vm.flash(msg ?: "导入失败") }
        }
    }

    val importer = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { r -> r.readText() }
            }.getOrNull()
            if (text.isNullOrBlank()) vm.flash("读取失败")
            else vm.importBackup(text) { err -> vm.flash(err ?: "导入完成") }
        }
    }

    androidx.activity.compose.BackHandler(
        enabled = editing != null || adding || showPrompt || showMemory
    ) {
        editing = null
        adding = false
        showPrompt = false
        showMemory = false
    }
    androidx.activity.compose.BackHandler(
        enabled = editing == null && !adding && (showAppearance || showFont || showVoice)
    ) {
        showAppearance = false
        showFont = false
        showVoice = false
    }

    Box(Modifier.fillMaxSize().background(pageBg)) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏：圆形返回 + 居中标题
            Box(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(52.dp)
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 14.dp)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(cs.outlineVariant)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack, "返回",
                        Modifier.size(18.dp), tint = cs.onSurface
                    )
                }
                Text(
                    "设置",
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onSurface,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp)
            ) {
                item {
                    GroupLabel("服务")
                    Card {
                        for ((i, p) in s.providers.withIndex()) {
                            SettingRow(
                                icon = Icons.Rounded.Key,
                                title = p.name,
                                value = if (p.apiKey.isBlank()) "未填 Key" else p.defaultModel,
                                valueColor = if (p.apiKey.isBlank()) cs.error else cs.onSurfaceVariant,
                                onClick = { editing = p }
                            )
                            if (i != s.providers.lastIndex) RowGap()
                        }
                        SettingRow(
                            icon = Icons.Rounded.Add,
                            title = "添加第三方服务",
                            tint = cs.primary,
                            titleColor = cs.primary,
                            onClick = { adding = true }
                        )
                    }
                }

                item {
                    GroupLabel("应用")
                    Card {
                        SettingRow(
                            icon = Icons.Rounded.Language,
                            title = "语言",
                            value = "中文（简体中文）"
                        )
                        RowGap()
                        SettingRow(
                            icon = Icons.Rounded.LightMode,
                            title = "外观",
                            value = when (s.theme) {
                                "dark" -> "深色"; "light" -> "浅色"; else -> "系统"
                            },
                            onClick = { showAppearance = true }
                        )
                        RowGap()
                        SettingRow(
                            icon = Icons.Rounded.TextFields,
                            title = "字体大小",
                            value = "${s.fontSize.toInt()}",
                            onClick = { showFont = true }
                        )
                        RowGap()
                        SettingRow(
                            icon = Icons.Rounded.Tune,
                            title = "个性化",
                            value = if (s.showReasoning) "展示思考" else "收起思考",
                            onClick = { vm.updateSettings { it.copy(showReasoning = !it.showReasoning) } }
                        )
                        RowGap()
                        SettingRow(
                            icon = Icons.Rounded.Description,
                            title = "系统提示词",
                            value = if (s.globalSystemPrompt.isBlank()) "未设置" else "已设置",
                            onClick = { showPrompt = true }
                        )
                        RowGap()
                        SettingRow(
                            icon = Icons.Rounded.Psychology,
                            title = "记忆",
                            value = if (s.memories.isEmpty()) "空" else "${s.memories.size} 条",
                            onClick = { showMemory = true }
                        )
                    }
                }

                item {
                    GroupLabel("语音")
                    Card {
                        SettingRow(
                            icon = Icons.Rounded.VolumeUp,
                            title = "朗读音色",
                            value = if (s.speakMode == "api")
                                "API·" + if (s.voiceId == "tide") "暗潮" else "海星"
                            else "系统 TTS",
                            onClick = { showVoice = true }
                        )
                        RowGap()
                        SettingRow(
                            icon = Icons.Rounded.Mic,
                            title = "自动朗读回答",
                            value = if (s.autoSpeak) "开" else "关",
                            onClick = { vm.toggleSpeak() }
                        )
                        RowGap()
                        SettingRow(
                            icon = Icons.Rounded.RecordVoiceOver,
                            title = "识别方式",
                            value = when (s.asrEngine) {
                                "vosk" -> "本地模型"
                                "api" -> "DeepSeek 接口"
                                else -> "系统识别"
                            },
                            onClick = { showAsr = true }
                        )
                        RowGap()
                        SettingRow(
                            icon = Icons.Rounded.Download,
                            title = "本地识别模型",
                            value = if (com.shipyard.deepchat.audio.AsrModel.ready(ctx)) "已就绪" else "未下载",
                            onClick = { showVosk = true }
                        )
                    }
                }

                item {
                    GroupLabel("同步与数据")
                    Card {
                        WebDavSection(vm = vm, cfg = s.webdav)
                        RowGap()
                        SettingRow(
                            icon = Icons.Rounded.FileUpload,
                            title = "导出配置",
                            value = "json",
                            onClick = { exporter.launch("deepchat-backup.json") }
                        )
                        RowGap()
                        SettingRow(
                            icon = Icons.Rounded.FileDownload,
                            title = "导入配置",
                            onClick = {
                                importer.launch(arrayOf("application/json", "text/plain", "*/*"))
                            }
                        )
                        RowGap()
                        SettingRow(
                            icon = Icons.Rounded.Archive,
                            title = "导入官方数据包",
                            value = "zip",
                            onClick = {
                                zipImporter.launch(
                                    arrayOf(
                                        "application/zip",
                                        "application/x-zip-compressed",
                                        "*/*"
                                    )
                                )
                            }
                        )
                        RowGap()
                        SettingRow(
                            icon = Icons.Rounded.Link,
                            title = "从分享链接导入",
                            onClick = { showLink = true }
                        )
                    }
                }

                item {
                    GroupLabel("关于")
                    Card {
                        SettingRow(
                            icon = Icons.Rounded.Info,
                            title = "版本",
                            value = "1.0 · 非官方客户端"
                        )
                        RowGap()
                        SettingRow(
                            icon = Icons.Rounded.Description,
                            title = "导出全部会话",
                            onClick = {
                                val all = st.conversations.joinToString("\n\n---\n\n") {
                                    Store.toMarkdown(it)
                                }
                                if (all.isBlank()) vm.flash("还没有会话")
                                else {
                                    val i = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, all)
                                    }
                                    ctx.startActivity(Intent.createChooser(i, "导出全部会话"))
                                }
                            }
                        )
                        RowGap()
                        SettingRow(
                            icon = Icons.Rounded.Delete,
                            title = "清空本机所有会话",
                            titleColor = cs.error,
                            onClick = { vm.clearAll() }
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "直连 DeepSeek 公开 API 与任意 OpenAI 兼容端点\nAPI Key 只保存在本机，不经过任何第三方服务器",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 22.dp)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = editing != null || adding,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
        ) {
            ProviderEditor(
                initial = editing ?: Provider(
                    id = UUID.randomUUID().toString(),
                    name = "自定义服务",
                    baseUrl = "https://api.openai.com/v1",
                    models = listOf("gpt-4o-mini"),
                    defaultModel = "gpt-4o-mini"
                ),
                isNew = adding,
                onSave = { p ->
                    vm.upsertProvider(p)
                    if (adding) vm.setActiveProvider(p.id)
                    editing = null; adding = false
                },
                onDelete = { p ->
                    vm.deleteProvider(p.id); editing = null; adding = false
                },
                onCancel = { editing = null; adding = false },
                onFetchModels = { p, cb -> vm.fetchModels(p, cb) },
                onTest = { p, cb -> vm.testProvider(p, cb) }
            )
        }

        if (showAppearance) {
            ChoiceSheet(
                title = "外观",
                options = listOf("system" to "系统", "light" to "浅色", "dark" to "深色"),
                selected = s.theme,
                onPick = { vm.updateSettings { st2 -> st2.copy(theme = it) } },
                onDismiss = { showAppearance = false }
            )
        }
        if (showFont) {
            FontSheet(
                current = s.fontSize,
                onChange = { v -> vm.updateSettings { it.copy(fontSize = v) } },
                onDismiss = { showFont = false }
            )
        }
        if (showVoice) {
            VoiceSheet(vm = vm, onDismiss = { showVoice = false })
        }
        if (showPrompt) {
            PromptDialog(
                current = s.globalSystemPrompt,
                onSave = { vm.setGlobalSystemPrompt(it); showPrompt = false },
                onDismiss = { showPrompt = false }
            )
        }
        if (showMemory) {
            MemoryDialog(
                memories = s.memories,
                onAdd = { vm.addMemory(it) },
                onRemove = { vm.removeMemory(it) },
                onDismiss = { showMemory = false }
            )
        }
        if (showLink) {
            LinkImportDialog(
                onImport = { u, cb -> vm.importShareLink(u, cb) },
                onDismiss = { showLink = false }
            )
        }
        if (showAsr) {
            ChoiceSheet(
                title = "识别方式",
                options = listOf(
                    "system" to "系统识别（免费，精度看手机）",
                    "vosk" to "本地模型（离线，需下 42MB）",
                    "api" to "DeepSeek 接口（需充值）"
                ),
                selected = s.asrEngine,
                onPick = { vm.updateSettings { st -> st.copy(asrEngine = it) } },
                onDismiss = { showAsr = false }
            )
        }
        if (showVosk) {
            VoskSheet(
                ready = com.shipyard.deepchat.audio.AsrModel.ready(ctx),
                onDownload = { prog, done -> vm.downloadVoskModel(prog, done) },
                onImport = { voskTree.launch(null) },
                onDelete = {
                    vm.deleteVoskModel()
                    vm.flash("模型已删除")
                },
                onDismiss = { showVosk = false }
            )
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 26.dp, top = 18.dp, bottom = 8.dp)
    )
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.75f),
                RoundedCornerShape(14.dp)
            )
            .padding(vertical = 4.dp),
        content = content
    )
}

@Composable
private fun RowGap() {
    Spacer(
        Modifier
            .padding(start = 52.dp, end = 14.dp)
            .height(1.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    )
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    value: String? = null,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .height(54.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(21.dp), tint = tint)
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = titleColor,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .widthIn(max = 150.dp)
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
            Modifier.size(18.dp), tint = cs.onSurfaceVariant.copy(alpha = 0.45f)
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
            if (subtitle.isNotBlank()) Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun WebDavSection(vm: ChatViewModel, cfg: WebDavConfig) {
    val cs = MaterialTheme.colorScheme
    var enabled by remember(cfg.enabled) { mutableStateOf(cfg.enabled) }
    var url by remember(cfg.url) { mutableStateOf(cfg.url) }
    var user by remember(cfg.user) { mutableStateOf(cfg.user) }
    var pass by remember(cfg.pass) { mutableStateOf(cfg.pass) }
    var path by remember(cfg.path) { mutableStateOf(cfg.path) }
    var auto by remember(cfg.autoSync) { mutableStateOf(cfg.autoSync) }

    ToggleRow(
        "WebDAV 同步",
        if (cfg.lastSync > 0) "上次 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            .format(Date(cfg.lastSync)) else "会话以 json 存在网盘",
        enabled
    ) { enabled = it }

    if (enabled) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("服务器地址") },
                placeholder = { Text("https://dav.jianguoyun.com/dav") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = user, onValueChange = { user = it },
                label = { Text("账号") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = pass, onValueChange = { pass = it },
                label = { Text("应用密码") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = path, onValueChange = { path = it },
                label = { Text("目录") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            ToggleRow("启动时自动同步", "", auto) { auto = it }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "保存并同步",
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            vm.updateSettings {
                                it.copy(
                                    webdav = it.webdav.copy(
                                        enabled = true, url = url.trim(), user = user.trim(),
                                        pass = pass, path = path.trim(), autoSync = auto
                                    )
                                )
                            }
                            vm.syncNow()
                        }
                        .padding(vertical = 10.dp, horizontal = 4.dp)
                )
                Spacer(Modifier.width(16.dp))
                Icon(
                    Icons.Rounded.CloudSync, null,
                    Modifier.size(16.dp), tint = cs.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    vm.state.value.syncState.ifBlank { "未同步" },
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant
                )
            }
        }
    } else {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { enabled = true }
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(
                "开通 WebDAV，把会话同步到坚果云或自建网盘",
                style = MaterialTheme.typography.labelSmall,
                color = cs.primary
            )
        }
    }
}

@Composable
private fun ProviderEditor(
    initial: Provider,
    isNew: Boolean,
    onSave: (Provider) -> Unit,
    onDelete: (Provider) -> Unit,
    onCancel: () -> Unit,
    onFetchModels: (Provider, (List<String>?, String?) -> Unit) -> Unit,
    onTest: (Provider, (String?, String?) -> Unit) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var name by remember { mutableStateOf(initial.name) }
    var base by remember { mutableStateOf(initial.baseUrl) }
    var key by remember { mutableStateOf(initial.apiKey) }
    var models by remember { mutableStateOf(initial.models.joinToString(", ")) }
    var defaultModel by remember { mutableStateOf(initial.defaultModel) }
    var kind by remember { mutableStateOf(initial.kind) }
    var thinking by remember { mutableStateOf(initial.supportsThinking) }
    var web by remember { mutableStateOf(initial.supportsWeb) }
    var vision by remember { mutableStateOf(initial.supportsVision) }
    var temp by remember { mutableStateOf(initial.temperature?.toString() ?: "") }
    var maxTok by remember { mutableStateOf(initial.maxTokens?.toString() ?: "") }
    var sys by remember { mutableStateOf(initial.systemPrompt) }
    var loadingModels by remember { mutableStateOf(false) }
    var fetchErr by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testMsg by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(if (isDarkTheme()) cs.background else cs.surfaceVariant)) {
        Box(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(52.dp)
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 14.dp)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(cs.outlineVariant)
                    .clickable(onClick = onCancel),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack, "返回",
                    Modifier.size(18.dp), tint = cs.onSurface
                )
            }
            Text(
                if (isNew) "添加服务" else "编辑服务",
                style = MaterialTheme.typography.titleSmall,
                color = cs.onSurface,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 14.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(cs.surface)
                    .padding(14.dp)
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = base, onValueChange = { base = it },
                    label = { Text("Base URL") },
                    placeholder = { Text("https://api.deepseek.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text("API Key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = models, onValueChange = { models = it },
                        label = { Text("模型列表（逗号分隔）") },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(
                        enabled = !loadingModels,
                        onClick = {
                            loadingModels = true
                            fetchErr = null
                            onFetchModels(
                                initial.copy(
                                    name = name.trim(),
                                    baseUrl = base.trim(),
                                    apiKey = key.trim(),
                                    kind = kind
                                )
                            ) { list, e ->
                                loadingModels = false
                                if (list != null) {
                                    models = list.joinToString(", ")
                                    if (defaultModel.isBlank()) defaultModel = list.firstOrNull() ?: ""
                                } else {
                                    fetchErr = e
                                }
                            }
                        }
                    ) {
                        Text(if (loadingModels) "获取中" else "获取")
                    }
                }
                fetchErr?.let {
                    Text(
                        "拉取失败：$it",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.error,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = defaultModel, onValueChange = { defaultModel = it },
                    label = { Text("默认模型") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(14.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(cs.surface)
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    for ((k, label) in listOf("deepseek" to "DeepSeek", "openai" to "OpenAI 兼容")) {
                        val on = kind == k
                        Box(
                            Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (on) cs.primaryContainer else cs.surfaceVariant)
                                .clickable { kind = k }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (on) cs.primary else cs.onSurfaceVariant
                            )
                        }
                    }
                }
                ToggleRow("思考模式", "发送 thinking 参数", thinking) { thinking = it }
                ToggleRow("联网搜索", "DeepSeek 内置服务端工具", web) { web = it }
                ToggleRow("图像输入", "允许发送图片", vision) { vision = it }
            }

            Spacer(Modifier.height(14.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(cs.surface)
                    .padding(14.dp)
            ) {
                OutlinedTextField(
                    value = temp, onValueChange = { temp = it },
                    label = { Text("temperature（可空）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = maxTok, onValueChange = { maxTok = it },
                    label = { Text("max_tokens（可空）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = sys, onValueChange = { sys = it },
                    label = { Text("系统提示词（可空）") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(cs.primary)
                    .clickable {
                        val list = models.split(",", "，", "\n")
                            .map { it.trim() }.filter { it.isNotBlank() }
                        onSave(
                            initial.copy(
                                name = name.trim().ifBlank { "未命名" },
                                baseUrl = base.trim(),
                                apiKey = key.trim(),
                                models = list.ifEmpty { listOf(defaultModel.trim()) },
                                defaultModel = defaultModel.trim().ifBlank { list.firstOrNull() ?: "" },
                                kind = kind,
                                supportsThinking = thinking,
                                supportsWeb = web,
                                supportsVision = vision,
                                temperature = temp.toFloatOrNull(),
                                maxTokens = maxTok.toIntOrNull(),
                                systemPrompt = sys.trim()
                            )
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("保存", color = androidx.compose.ui.graphics.Color.White)
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    enabled = !testing,
                    onClick = {
                        testing = true
                        testMsg = null
                        val list = models.split(",", "，", "\n")
                            .map { it.trim() }.filter { it.isNotBlank() }
                        onTest(
                            initial.copy(
                                name = name.trim(),
                                baseUrl = base.trim(),
                                apiKey = key.trim(),
                                models = list,
                                defaultModel = defaultModel.trim().ifBlank {
                                    list.firstOrNull() ?: ""
                                },
                                kind = kind
                            )
                        ) { ok, err ->
                            testing = false
                            testOk = ok != null
                            testMsg = ok ?: ("失败：$err")
                        }
                    }
                ) {
                    Text(if (testing) "测试中…" else "测试连接")
                }
                if (testMsg != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        testMsg.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (testOk) cs.primary else cs.error,
                        maxLines = 2
                    )
                }
            }
            if (!isNew) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .clickable { onDelete(initial) },
                    contentAlignment = Alignment.Center
                ) {
                    Text("删除这个服务", color = cs.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun ChoiceSheet(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxWidth(0.78f)
                .clip(RoundedCornerShape(16.dp))
                .background(cs.surface)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = cs.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(cs.outlineVariant)
            )
            for ((k, label) in options) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(k); onDismiss() }
                        .padding(horizontal = 20.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioDot(on = k == selected)
                    Spacer(Modifier.width(14.dp))
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(cs.outlineVariant)
            )
            Text(
                "确认",
                color = cs.primary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDismiss() }
                    .padding(vertical = 15.dp)
            )
        }
        }
    }
}

@Composable
private fun RadioDot(on: Boolean) {
    val cs = MaterialTheme.colorScheme
    if (on) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(cs.primary),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(androidx.compose.ui.graphics.Color.White)
            )
        }
    } else {
        androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
            drawCircle(
                color = cs.onSurfaceVariant.copy(alpha = 0.45f),
                radius = size.minDimension / 2 - 1.dp.toPx(),
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx())
            )
        }
    }
}

@Composable
private fun FontSheet(current: Float, onChange: (Float) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxWidth(0.78f)
                .clip(RoundedCornerShape(16.dp))
                .background(cs.surface)
                .padding(20.dp)
        ) {
            Text("字体大小", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            Spacer(Modifier.height(14.dp))
            Text(
                "预览文字大小",
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = current.sp),
                color = cs.onSurface
            )
            Spacer(Modifier.height(16.dp))
            Slider(
                value = current,
                onValueChange = onChange,
                valueRange = 13f..21f,
                steps = 7
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("A", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Text("A", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "完成",
                color = cs.primary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDismiss() }
                    .padding(vertical = 10.dp)
            )
        }
        }
    }
}

/** 系统提示词编辑 */
@Composable
private fun PromptDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var text by remember { mutableStateOf(current) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .fillMaxWidth(0.80f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(cs.surface)
            ) {
                Text(
                    "系统提示词",
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(cs.outlineVariant)
                )
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "每个新会话默认带上，会话里单独设置的优先",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        placeholder = {
                            Text(
                                "例如：你是简洁直接的中文助手，回答不要客套",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(cs.outlineVariant)
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        "取消",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onDismiss() }
                            .padding(vertical = 15.dp)
                    )
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(49.dp)
                            .background(cs.outlineVariant)
                    )
                    Text(
                        "保存",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.primary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSave(text) }
                            .padding(vertical = 15.dp)
                    )
                }
            }
        }
    }
}

/** 长期记忆：手动添加的事实/偏好，每次请求附在系统提示词后面 */
@Composable
private fun MemoryDialog(
    memories: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var input by remember { mutableStateOf("") }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .fillMaxWidth(0.82f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(cs.surface)
            ) {
                Text(
                    "记忆",
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(cs.outlineVariant)
                )

                Text(
                    "写进去的事实会在每次对话时自动带给模型，只存在本机",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )

                if (memories.isEmpty()) {
                    Text(
                        "还没有记忆，比如「我在读初三，喜欢折腾数码」",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)
                    )
                } else {
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                    ) {
                        items(memories.reversed()) { m ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    m,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurface,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(vertical = 9.dp)
                                )
                                IconButton(
                                    onClick = { onRemove(m) },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_official_trash), "删除",
                                        Modifier.size(15.dp), tint = cs.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = {
                            Text("添加一条记忆", style = MaterialTheme.typography.bodySmall)
                        },
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                if (input.isBlank()) cs.secondaryContainer else cs.primary
                            )
                            .clickable {
                                if (input.isNotBlank()) {
                                    onAdd(input)
                                    input = ""
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Add, "添加",
                            Modifier.size(18.dp),
                            tint = if (input.isBlank()) cs.onSurfaceVariant else Color.White
                        )
                    }
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(cs.outlineVariant)
                )
                Text(
                    "完成",
                    color = cs.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDismiss() }
                        .padding(vertical = 15.dp)
                )
            }
        }
    }
}
@Composable
private fun VoiceSheet(vm: ChatViewModel, onDismiss: () -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    val s = st.settings
    val cs = MaterialTheme.colorScheme
    var mode by remember { mutableStateOf(s.speakMode) }
    var voice by remember { mutableStateOf(s.voiceId) }
    var sysVoice by remember { mutableStateOf(s.systemVoice) }
    val sysList = remember(mode) {
        if (mode == "system") vm.systemVoices() else emptyList()
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .fillMaxWidth(0.78f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(cs.surface)
            ) {
                Text(
                    "朗读音色",
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(cs.outlineVariant)
                )

                Text(
                    "朗读方式",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp)
                )
                Row(
                    Modifier.padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for ((k, label) in listOf("system" to "系统 TTS", "api" to "DeepSeek API")) {
                        val on = mode == k
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (on) cs.primaryContainer else cs.surfaceVariant)
                                .clickable { mode = k }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (on) cs.primary else cs.onSurfaceVariant
                            )
                        }
                    }
                }

                Text(
                    if (mode == "api") "音色（按字符计费）" else "系统音色",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp)
                )

                if (mode == "api") {
                    for ((id, label) in listOf("stella" to "海星 · 女声", "tide" to "暗潮 · 男声")) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { voice = id }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioDot(on = voice == id)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = cs.onSurface
                            )
                        }
                    }
                } else {
                    if (sysList.isEmpty()) {
                        Text(
                            "系统没有可用的中文语音，可在系统设置里安装语音包",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                        )
                    } else {
                        Column(
                            Modifier
                                .height(180.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            for ((name, label) in sysList.take(20)) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { sysVoice = name }
                                        .padding(horizontal = 20.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioDot(on = sysVoice == name)
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = cs.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(cs.outlineVariant)
                )
                Text(
                    "确认",
                    color = cs.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            vm.updateSettings {
                                it.copy(
                                    speakMode = mode,
                                    voiceId = voice,
                                    systemVoice = sysVoice
                                )
                            }
                            onDismiss()
                        }
                        .padding(vertical = 15.dp)
                )
            }
        }
    }
}

/** 从官方分享链接导入一段对话 */
@Composable
private fun LinkImportDialog(
    onImport: (String, (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .fillMaxWidth(0.82f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(cs.surface)
            ) {
                Text(
                    "从分享链接导入",
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(cs.outlineVariant)
                )
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "粘贴 DeepSeek 分享链接，例如 https://chat.deepseek.com/share/xxxxx",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        placeholder = { Text("https://chat.deepseek.com/share/...") },
                        textStyle = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    msg?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ok) cs.primary else cs.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(cs.outlineVariant)
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        "取消",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onDismiss() }
                            .padding(vertical = 15.dp)
                    )
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(49.dp)
                            .background(cs.outlineVariant)
                    )
                    Text(
                        if (busy) "导入中…" else "导入",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.primary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                if (busy || url.isBlank()) return@clickable
                                busy = true
                                msg = null
                                onImport(url) { err ->
                                    busy = false
                                    ok = err == null
                                    if (err == null) onDismiss() else msg = err
                                }
                            }
                            .padding(vertical = 15.dp)
                    )
                }
            }
        }
    }
}

/** 本地识别模型：下载 / 从文件夹导入 / 删除 */
@Composable
private fun VoskSheet(
    ready: Boolean,
    onDownload: ((Int) -> Unit, (String?) -> Unit) -> Unit,
    onImport: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var progress by remember { mutableStateOf(-1) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var isReady by remember { mutableStateOf(ready) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .fillMaxWidth(0.82f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(cs.surface)
            ) {
                Text(
                    "本地识别模型",
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(cs.outlineVariant)
                )
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Vosk 中文小模型，42MB。下好之后完全离线，不花钱，录音也不上传。\n" +
                            "注意：官方源在国外，实测只有几 KB/s，建议用电脑下好再选文件夹导入。",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "状态",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (isReady) "已就绪" else "未下载",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isReady) cs.primary else cs.error
                        )
                    }
                    if (progress in 0..100) {
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "$progress%",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant
                        )
                    }
                    msg?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isReady) cs.primary else cs.error,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        androidx.compose.material3.Button(
                            enabled = !busy && !isReady,
                            onClick = {
                                busy = true
                                msg = null
                                progress = 0
                                onDownload({ p -> progress = p }) { err ->
                                    busy = false
                                    progress = -1
                                    if (err == null) {
                                        isReady = true
                                        msg = "下载完成，可以用了"
                                    } else {
                                        msg = err
                                    }
                                }
                            }
                        ) {
                            Text(if (busy) "下载中" else "下载模型")
                        }
                        androidx.compose.material3.OutlinedButton(
                            enabled = !busy,
                            onClick = onImport
                        ) {
                            Text("选文件夹导入")
                        }
                    }
                    if (isReady) {
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.TextButton(
                            onClick = {
                                onDelete()
                                isReady = false
                                msg = null
                            }
                        ) {
                            Text("删除模型", color = cs.error)
                        }
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(cs.outlineVariant)
                )
                Text(
                    "完成",
                    color = cs.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDismiss() }
                        .padding(vertical = 15.dp)
                )
            }
        }
    }
}
