package com.shipyard.deepchat.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shipyard.deepchat.R
import com.shipyard.deepchat.data.Conversation
import com.shipyard.deepchat.data.versionsOf
import com.shipyard.deepchat.data.visibleMessages
import com.shipyard.deepchat.data.Msg
import com.shipyard.deepchat.data.Store
import com.shipyard.deepchat.ui.theme.DeepChatTheme
import com.shipyard.deepchat.vm.ChatViewModel
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatApp(vm: ChatViewModel) {
    val st by vm.state.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Msg?>(null) }
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(st.message) {
        if (st.message != null) {
            kotlinx.coroutines.delay(2400)
            vm.consumeMessage()
        }
    }

    DeepChatTheme(theme = st.settings.theme, fontScale = st.settings.fontSize / 16f) {
        val cs = MaterialTheme.colorScheme
        androidx.activity.compose.BackHandler(enabled = showSettings) { showSettings = false }
        Box(Modifier.fillMaxSize().background(cs.background)) {
            ModalNavigationDrawer(
                drawerState = drawer,
                gesturesEnabled = !showSettings,
                scrimColor = Color.Black.copy(alpha = 0.25f),
                drawerContent = {
                    DrawerContent(
                        vm = vm,
                        drawer = drawer,
                        onSettings = {
                            scope.launch { drawer.close() }
                            showSettings = true
                        },
                        onRename = { renaming = it },
                        onPickModel = { showPicker = true }
                    )
                }
            ) {
                Box(Modifier.fillMaxSize()) {
                    ChatPane(
                        vm = vm,
                        onOpenDrawer = { scope.launch { drawer.open() } },
                        onRename = { renaming = it },
                        onEdit = { editing = it }
                    )

                    AnimatedVisibility(
                        visible = showSettings,
                        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
                    ) {
                        SettingsScreen(vm = vm, onBack = { showSettings = false })
                    }
                }
            }
            // 提示统一放屏幕中间的小胶囊，不挡底部输入区
            st.message?.let { msg ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xE6101010))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        }

        if (showPicker) {
            ModalBottomSheet(
                onDismissRequest = { showPicker = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                ModelPicker(vm = vm, onDone = { showPicker = false })
            }
        }

        editing?.let { msg ->
            var text by remember(msg.id) { mutableStateOf(msg.content) }
            AlertDialog(
                onDismissRequest = { editing = null },
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text("修改消息") },
                text = {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        textStyle = MaterialTheme.typography.bodyLarge
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.editAndResend(msg.id, text)
                        editing = null
                    }) { Text("发送") }
                },
                dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }
            )
        }

        renaming?.let { conv ->
            var text by remember(conv.id) { mutableStateOf(conv.title) }
            AlertDialog(
                onDismissRequest = { renaming = null },
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text("重命名") },
                text = {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = { vm.rename(conv.id, text); renaming = null }) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } }
            )
        }
    }
}

@Composable
private fun ChatPane(
    vm: ChatViewModel,
    onOpenDrawer: () -> Unit,
    onRename: (Conversation) -> Unit,
    onEdit: (Msg) -> Unit
) {
    val st by vm.state.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val conv = st.current
    val messages = conv?.visibleMessages().orEmpty()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val lastLen = messages.lastOrNull()?.content?.length ?: 0
    val lastReasonLen = messages.lastOrNull()?.reasoning?.length ?: 0

    var recActive by remember { mutableStateOf(false) }
    var recCancel by remember { mutableStateOf(false) }
    var recLevel by remember { mutableFloatStateOf(0f) }
    var recSpectrum by remember { mutableStateOf<FloatArray?>(null) }
    var showUsage by remember { mutableStateOf(false) }

    LaunchedEffect(lastLen, lastReasonLen, messages.size, st.streaming) {
        if (messages.isNotEmpty()) {
            runCatching { listState.scrollToItem(messages.lastIndex) }
        }
    }

    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 1
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(52.dp)
                    .padding(start = 6.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onOpenDrawer, modifier = Modifier.size(42.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_official_menu), "会话列表",
                        Modifier.size(23.dp), tint = cs.onSurface
                    )
                }
                Spacer(Modifier.width(2.dp))
                if (messages.isNotEmpty()) {
                    Text(
                        text = conv?.title.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { conv?.let(onRename) }
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }

                ContextRing(
                    used = st.contextTokens,
                    limit = st.settings.contextLimit,
                    onClick = { showUsage = true }
                )
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = { vm.toggleSpeak() }, modifier = Modifier.size(38.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_official_volume_off),
                        if (st.settings.autoSpeak) "关闭自动朗读" else "开启自动朗读",
                        Modifier.size(23.dp),
                        tint = if (st.settings.autoSpeak) cs.primary else cs.onSurface
                    )
                }
                IconButton(onClick = { vm.newChat() }, modifier = Modifier.size(38.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_official_new_chat), "新对话",
                        Modifier.size(23.dp), tint = cs.onSurface
                    )
                }
            }

            Box(
                Modifier
                    .weight(1f)
                    .pointerInput(Unit) {
                        // 点空白处收起键盘
                        detectTapGestures(onTap = { focusManager.clearFocus() })
                    }
            ) {
                if (messages.isEmpty()) {
                    Welcome()
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 14.dp)
                    ) {
                        items(messages, key = { it.id }) { m ->
                            val versions = conv?.versionsOf(m.id).orEmpty()
                            MessageItem(
                                msg = m,
                                streaming = st.streaming && st.streamingId == m.id,
                                isLast = m.id == messages.last().id,
                                showReasoning = st.settings.showReasoning,
                                onRegenerate = { vm.regenerate() },
                                onEdit = { onEdit(m) },
                                onSpeak = { vm.speakNow(it) },
                                versionIds = versions.map { it.id },
                                versionIndex = versions.indexOfFirst { it.id == m.id }
                                    .coerceAtLeast(0),
                                onSwitchVersion = { vm.switchVersion(it) }
                            )
                        }
                    }
                }

                if (st.statusText.isNotBlank()) {
                    Row(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(cs.surfaceVariant)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(10.dp), strokeWidth = 1.3.dp, color = cs.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            st.statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant
                        )
                    }
                }

                val fabAlpha by androidx.compose.animation.core.animateFloatAsState(
                    if (messages.isNotEmpty() && !atBottom) 1f else 0f,
                    label = "fab"
                )
                if (fabAlpha > 0.02f) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 10.dp)
                            .alpha(fabAlpha)
                    ) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .shadow(3.dp, CircleShape)
                                .clip(CircleShape)
                                .background(cs.surface)
                                .border(1.dp, cs.outline, CircleShape)
                                .clickable {
                                    scope.launch {
                                        runCatching {
                                            listState.animateScrollToItem(messages.lastIndex)
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.ArrowUpward, "回到底部",
                                Modifier.size(18.dp), tint = cs.onSurface
                            )
                        }
                    }
                }
            }

            if (conv != null) {
                Composer(
                    streaming = st.streaming,
                    thinking = conv.thinking,
                    web = conv.webSearch,
                    onSend = { t, imgs -> vm.send(t, imgs) },
                    onStop = { vm.stop() },
                    onToggleThinking = { vm.setThinking(!conv.thinking) },
                    onToggleWeb = { vm.setWeb(!conv.webSearch) },
                    onAudioReady = { file, cb ->
                        vm.transcribe(file) { text -> cb(text) }
                    },
                    onRecordUi = { active, cancel, level ->
                        recActive = active
                        recCancel = cancel
                        recLevel = level
                        if (!active) recSpectrum = null
                    },
                    onSpectrum = { recSpectrum = it },
                    asrEngine = st.settings.asrEngine,
                    asrAutoFill = st.settings.asrAutoFill,
                    onFlash = { vm.flash(it) }
                )
            }
        }

        // 录音浮层：不消费触摸，手势继续由输入卡片处理
        if (recActive) {
            RecordingOverlay(cancel = recCancel, level = recLevel, spectrum = recSpectrum)
        }

        if (showUsage) {
            UsageDialog(
                used = st.contextTokens,
                limit = st.settings.contextLimit,
                todayTokens = st.todayUsage.tokens,
                todayCalls = st.todayUsage.calls,
                onDismiss = { showUsage = false }
            )
        }
    }
}

/** 上下文与今日用量 */
@Composable
private fun UsageDialog(
    used: Int,
    limit: Int,
    todayTokens: Int,
    todayCalls: Int,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val usedK = used / 1000f
    val pct = if (limit > 0) used * 100f / limit else 0f

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(cs.surface)
        ) {
            Text(
                "用量",
                style = MaterialTheme.typography.titleSmall,
                color = cs.onSurface,
                textAlign = TextAlign.Center,
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

            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Row {
                    Text(
                        "本次上下文",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "%.1fK / %dK".format(usedK, limit / 1000),
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurface
                    )
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(cs.secondaryContainer)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(pct.coerceIn(0f, 1f))
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(cs.primary)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "占窗口 %.1f%%".format(pct),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant
                )

                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(cs.outlineVariant)
                )
                Spacer(Modifier.height(14.dp))

                Row {
                    Text(
                        "今日用量",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${todayCalls} 次 · ${"%.1f".format(todayTokens / 1000f)}K tokens",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurface
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "token 数为本地估算，不含服务端计费口径",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(cs.outlineVariant)
            )
            Text(
                "知道了",
                color = cs.primary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDismiss() }
                    .padding(vertical = 15.dp)
            )
        }
    }
}

/** 顶栏上下文占用环 */
@Composable
fun ContextRing(used: Int, limit: Int, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val frac = (used.toFloat() / limit.coerceAtLeast(1)).coerceIn(0f, 1f)
    Canvas(
        Modifier
            .size(26.dp)
            .clickable(onClick = onClick)
            .padding(3.dp)
    ) {
        val stroke = size.minDimension * 0.16f
        val r = size.minDimension / 2 - stroke / 2
        drawCircle(
            color = cs.onSurfaceVariant.copy(alpha = 0.22f),
            radius = r,
            style = Stroke(stroke)
        )
        if (frac > 0.001f) {
            drawArc(
                color = cs.primary,
                startAngle = -90f,
                sweepAngle = 360f * frac,
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = Size(r * 2, r * 2),
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
    }
}

/** 长按说话的浮层，按官方那张图做的：白到蓝的渐变 + 提示 + 波形 */
@Composable
private fun RecordingOverlay(cancel: Boolean, level: Float, spectrum: FloatArray?) {
    val grad = if (cancel) {
        listOf(Color.White, Color(0xFFFF7A7A))
    } else {
        listOf(Color.White, Color(0xFF6B9BFF))
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(grad))
    ) {
        Text(
            if (cancel) "松开手指，取消发送" else "松手发送，上滑取消",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 120.dp)
                .fillMaxWidth()
        )

        // 实时频谱：100–4000Hz 对数分频，低频在左
        Canvas(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 86.dp)
                .fillMaxWidth(0.84f)
                .height(40.dp)
        ) {
            val bars = spectrum ?: FloatArray(60) { 0f }
            val n = bars.size
            val gapPx = 2.dp.toPx()
            val barW = ((size.width - gapPx * (n - 1)) / n).coerceAtLeast(1f)
            val minH = 5.dp.toPx()
            val maxH = size.height
            for (i in 0 until n) {
                val level = bars[i].coerceIn(0f, 1f)
                val h = minH + (maxH - minH) * level
                val x = i * (barW + gapPx)
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.9f),
                    topLeft = Offset(x, size.height - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                )
            }
        }
    }
}

/** 官方的菜单图标是两道横线 */
@Composable
fun TwoLineMenu(tint: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val stroke = w * 0.085f
        val left = w * 0.16f
        val right = w * 0.84f
        val y1 = w * 0.36f
        val y2 = w * 0.62f
        drawLine(tint, Offset(left, y1), Offset(right, y1), stroke, StrokeCap.Round)
        drawLine(tint, Offset(left, y2), Offset(right, y2), stroke, StrokeCap.Round)
    }
}

@Composable
private fun Welcome() {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(76.dp)
        )
        Spacer(Modifier.height(18.dp))
        Text(
            "嗨！今天想聊些什么？",
            style = MaterialTheme.typography.bodyLarge,
            color = cs.onSurface
        )
    }
}

private fun greeting(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (h) {
        in 5..10 -> "早上好，有什么我能帮你的吗？"
        in 11..13 -> "中午好，有什么我能帮你的吗？"
        in 14..17 -> "下午好，有什么我能帮你的吗？"
        in 18..22 -> "晚上好，有什么我能帮你的吗？"
        else -> "夜深了，有什么我能帮你的吗？"
    }
}

@Composable
private fun DrawerContent(
    vm: ChatViewModel,
    drawer: DrawerState,
    onSettings: () -> Unit,
    onRename: (Conversation) -> Unit,
    onPickModel: () -> Unit
) {
    val st by vm.state.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var selectMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }

    val all = st.conversations
    val filtered = all.filter {
        query.text.isBlank() ||
            it.title.contains(query.text, true) ||
            it.preview.contains(query.text, true)
    }
    val grouped = filtered.groupBy { bucketOf(it.updatedAt) }
    val order = listOf("今天", "昨天", "7 天内", "更早")

    ModalDrawerSheet(
        drawerContainerColor = cs.background,
        modifier = Modifier.widthIn(max = 330.dp).fillMaxWidth(0.86f)
    ) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.statusBarsPadding().height(10.dp))

            if (selectMode) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "选择对话",
                        style = MaterialTheme.typography.titleSmall,
                        color = cs.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(cs.secondaryContainer)
                            .clickable {
                                selectMode = false
                                selected.clear()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Close, "退出多选",
                            Modifier.size(16.dp), tint = cs.onSurface
                        )
                    }
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .height(42.dp)
                        .clip(RoundedCornerShape(21.dp))
                        .background(cs.secondaryContainer)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painterResource(R.drawable.ic_official_search), null,
                        Modifier.size(17.dp), tint = cs.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.text.isEmpty()) Text(
                            "搜索对话内容...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant
                        )
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
                            cursorBrush = SolidColor(cs.primary),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            LazyColumn(Modifier.weight(1f).padding(top = 6.dp)) {
                for (g in order) {
                    val list = grouped[g] ?: continue
                    item(key = "h_$g") {
                        Text(
                            g,
                            style = MaterialTheme.typography.labelMedium,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp)
                        )
                    }
                    items(list, key = { it.id }) { c ->
                        ConversationRow(
                            conv = c,
                            active = c.id == st.current?.id,
                            selectMode = selectMode,
                            selected = selected.contains(c.id),
                            onClick = {
                                if (selectMode) {
                                    if (selected.contains(c.id)) selected.remove(c.id)
                                    else selected.add(c.id)
                                } else {
                                    vm.open(c.id)
                                    scope.launch { drawer.close() }
                                }
                            },
                            onLongClick = {
                                selectMode = true
                                if (!selected.contains(c.id)) selected.add(c.id)
                            },
                            onRename = { onRename(c) },
                            onPin = { vm.togglePin(c.id) },
                            onDelete = { vm.delete(c.id) },
                            onExport = {
                                val md = Store.toMarkdown(c)
                                val i = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, c.title)
                                    putExtra(Intent.EXTRA_TEXT, md)
                                }
                                ctx.startActivity(Intent.createChooser(i, "导出对话"))
                            }
                        )
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            "没有匹配的对话",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp)
                        )
                    }
                }
            }

            if (selectMode) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .height(54.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        Modifier
                            .weight(1f)
                            .clickable {
                                selected.forEach { id ->
                                    val c = all.firstOrNull { it.id == id }
                                    if (c != null && !c.pinned) vm.togglePin(id)
                                }
                                selected.clear()
                                selectMode = false
                            }
                            .padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_official_pin), null,
                            Modifier.size(17.dp), tint = cs.onSurface
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "置顶",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurface
                        )
                    }
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(22.dp)
                            .background(cs.outline)
                    )
                    Row(
                        Modifier
                            .weight(1f)
                            .clickable {
                                selected.forEach { vm.delete(it) }
                                selected.clear()
                                selectMode = false
                            }
                            .padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_official_trash), null,
                            Modifier.size(17.dp), tint = cs.onSurface
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "删除",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurface
                        )
                    }
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onPickModel() }
                            .padding(horizontal = 6.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                st.settings.activeProvider.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                st.current?.model?.ifBlank {
                                    st.settings.activeProvider.defaultModel
                                } ?: st.settings.activeProvider.defaultModel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = cs.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Row(
                        Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(cs.secondaryContainer)
                            .clickable(onClick = onSettings),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Settings, null,
                            Modifier.size(19.dp), tint = cs.onSurface
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            "设置",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurface
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

private fun bucketOf(ts: Long): String {
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val today = cal.timeInMillis
    return when {
        ts >= today -> "今天"
        ts >= today - 86_400_000L -> "昨天"
        ts >= today - 7 * 86_400_000L -> "7 天内"
        else -> "更早"
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conv: Conversation,
    active: Boolean,
    selectMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    when {
                        selected -> cs.primaryContainer
                        active -> cs.secondaryContainer
                        else -> Color.Transparent
                    }
                )
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(start = 12.dp, end = 4.dp)
                .height(50.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectMode) {
                Box(
                    Modifier
                        .size(19.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, if (selected) cs.primary else cs.outline, CircleShape)
                        .background(if (selected) cs.primary else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) Icon(
                        Icons.Rounded.Check, null,
                        Modifier.size(13.dp), tint = Color.White
                    )
                }
                Spacer(Modifier.width(11.dp))
            }
            Text(
                conv.title,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!selectMode) {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Rounded.MoreHoriz, "更多",
                        Modifier.size(18.dp),
                        tint = cs.onSurfaceVariant.copy(alpha = if (active) 0.9f else 0.55f)
                    )
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; onRename() })
            DropdownMenuItem(
                text = { Text(if (conv.pinned) "取消置顶" else "置顶") },
                onClick = { menu = false; onPin() }
            )
            DropdownMenuItem(
                text = { Text("导出文本") },
                onClick = { menu = false; onExport() }
            )
            DropdownMenuItem(
                text = { Text("删除", color = cs.error) },
                onClick = { menu = false; onDelete() }
            )
        }
    }
}

@Composable
private fun ModelPicker(vm: ChatViewModel, onDone: () -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val cur = st.current
    Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
        Text(
            "选择模型",
            style = MaterialTheme.typography.titleMedium,
            color = cs.onSurface,
            modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 8.dp)
        )
        for (p in st.settings.providers) {
            Text(
                p.name + if (p.apiKey.isBlank()) "（未配置 Key）" else "",
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp)
            )
            for (m in p.models) {
                val selected = cur?.model == m
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            vm.setActiveProvider(p.id)
                            vm.setModel(m)
                            onDone()
                        }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        m,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) cs.primary else cs.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (selected) Icon(
                        Icons.Rounded.Check, null, Modifier.size(16.dp), tint = cs.primary
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "在设置里管理服务商与 API Key",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
    }
}
