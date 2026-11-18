package com.shipyard.deepchat.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.shipyard.deepchat.R
import com.shipyard.deepchat.audio.AsrEngine
import com.shipyard.deepchat.audio.AsrModel
import com.shipyard.deepchat.audio.Recorder
import com.shipyard.deepchat.audio.SystemAsr
import com.shipyard.deepchat.audio.VoskAsr
import com.shipyard.deepchat.data.Store
import com.shipyard.deepchat.ui.theme.isDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 输入区，对齐官方结构：
 * 圆角卡片，上行文本，下行左侧两个胶囊、右侧附件与语音。
 * 长按文本区或按住语音键 = 录音，松手发送，上滑取消。
 */
@Composable
fun Composer(
    streaming: Boolean,
    thinking: Boolean,
    web: Boolean,
    onSend: (String, List<String>) -> Unit,
    onStop: () -> Unit,
    onToggleThinking: () -> Unit,
    onToggleWeb: () -> Unit,
    onAudioReady: ((File, (String?) -> Unit) -> Unit)? = null,
    onRecordUi: (active: Boolean, cancelHint: Boolean, level: Float) -> Unit,
    asrEngine: String = "system",
    asrAutoFill: Boolean = true,
    onFlash: (String) -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { Recorder(ctx) }
    val systemAsr = remember { SystemAsr(ctx) }
    val voskAsr = remember { VoskAsr(ctx, AsrModel.dir(ctx)) }
    var value by remember { mutableStateOf(TextFieldValue("")) }
    var pending by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var cancelHint by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }
    /** false = 文字态（默认）；true = 按住说话 */
    var voiceMode by remember { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // 授权后手指已经抬起，不能自动开录，提示重新长按
        android.widget.Toast.makeText(
            ctx,
            if (granted) "麦克风已授权，请再长按一次说话" else "需要麦克风权限才能说话",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            recorder.cancel()
            runCatching { systemAsr.release() }
            runCatching { voskAsr.release() }
        }
    }

    fun applyAsrResult(text: String) {
        val t = text.trim()
        if (t.isBlank()) return
        if (asrAutoFill) {
            value = TextFieldValue(t, TextRange(t.length))
            focused = true
        } else {
            onSend(t, pending)
            value = TextFieldValue("")
            pending = emptyList()
        }
    }

    fun bindAsr(e: AsrEngine) {
        e.onLevel = { if (recording) onRecordUi(true, cancelHint, it) }
        e.onFinal = { applyAsrResult(it) }
        e.onError = { msg ->
            recording = false
            onRecordUi(false, false, 0f)
            onFlash(msg)
        }
    }

    fun doStart() {
        if (recording) return
        recording = true
        cancelHint = false
        onRecordUi(true, false, 0f)
        when (asrEngine) {
            "vosk" -> {
                if (!AsrModel.ready(ctx)) {
                    recording = false
                    onRecordUi(false, false, 0f)
                    onFlash("本地模型还没下载，去设置 → 语音里下")
                    return
                }
                bindAsr(voskAsr)
                voskAsr.prepare { err ->
                    if (err != null) {
                        recording = false
                        onRecordUi(false, false, 0f)
                        onFlash("模型加载失败：$err")
                    } else if (recording && !voskAsr.start()) {
                        recording = false
                        onRecordUi(false, false, 0f)
                    }
                }
            }

            "api" -> if (!recorder.start()) {
                recording = false
                onRecordUi(false, false, 0f)
                onFlash("麦克风打不开")
            }

            else -> {
                bindAsr(systemAsr)
                if (!systemAsr.start()) {
                    recording = false
                    onRecordUi(false, false, 0f)
                }
            }
        }
    }

    fun beginRecording() {
        if (recording) return
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            perm.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        doStart()
    }

    fun finishRecording(cancel: Boolean) {
        if (!recording) return
        recording = false
        cancelHint = false
        onRecordUi(false, false, 0f)
        when (asrEngine) {
            "vosk" -> if (cancel) voskAsr.cancel() else voskAsr.stop()
            "api" -> if (cancel) recorder.cancel() else recorder.stop()?.let { f ->
                onAudioReady?.invoke(f) { text -> if (text != null) applyAsrResult(text) }
            }
            else -> if (cancel) systemAsr.cancel() else systemAsr.stop()
        }
    }

    LaunchedEffect(recording) {
        if (!recording || asrEngine != "api") return@LaunchedEffect
        while (recording) {
            if (!recorder.isRunning) {
                finishRecording(false)
                break
            }
            onRecordUi(true, cancelHint, recorder.level)
            delay(70)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val name = withContext(Dispatchers.IO) { savePicked(ctx, uri) }
            if (name != null) pending = pending + name
            busy = false
        }
    }

    fun fire() {
        val t = value.text.trim()
        if (t.isBlank() && pending.isEmpty()) return
        onSend(t, pending)
        value = TextFieldValue("")
        pending = emptyList()
    }

    val ready = value.text.isNotBlank() || pending.isNotEmpty()
    val cardBg = if (isDarkTheme()) cs.surfaceVariant else cs.surface

    Column(
        Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 6.dp,
                    shape = RoundedCornerShape(26.dp),
                    ambientColor = Color.Black.copy(alpha = 0.05f),
                    spotColor = Color.Black.copy(alpha = 0.05f)
                )
                .clip(RoundedCornerShape(26.dp))
                .background(cardBg)
                .border(1.dp, cs.outline.copy(alpha = 0.85f), RoundedCornerShape(26.dp))
                .padding(start = 15.dp, end = 9.dp, top = 13.dp, bottom = 8.dp)
                .animateContentSize()
        ) {
            if (pending.isNotEmpty()) {
                Row(
                    Modifier.padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (n in pending) {
                        Box {
                            AsyncImage(
                                model = Store.imageFile(ctx, n),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(2.dp)
                                    .size(17.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .clickable { pending = pending - n },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.Close, "移除",
                                    Modifier.size(11.dp), tint = Color.White
                                )
                            }
                        }
                    }
                }
            }

            // TextField 常驻，未聚焦时盖一层透明手势层：
            // 点击一定拿得到焦点；长按也不会触发系统的选择/粘贴菜单
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .heightIn(min = if (voiceMode) 36.dp else 24.dp),
                contentAlignment = if (voiceMode && value.text.isEmpty()) Alignment.Center
                else Alignment.CenterStart
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { fire() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged {
                            if (it.isFocused) hadFocus = true
                            else if (hadFocus) focused = false
                        }
                )
                if (value.text.isEmpty() && !focused) {
                    Text(
                        if (voiceMode) "按住说话" else "发消息或按住说话",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (voiceMode) FontWeight.Medium else FontWeight.Normal,
                        color = if (voiceMode) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.85f),
                        maxLines = 2
                    )
                }
                if (!focused) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .pointerInput(voiceMode) {
                                if (!voiceMode) {
                                    detectTapGestures(
                                        onTap = {
                                            runCatching { focusRequester.requestFocus() }
                                            runCatching { keyboard?.show() }
                                        },
                                        onLongPress = { /* 吃掉，别让输入框弹选择菜单 */ }
                                    )
                                    return@pointerInput
                                }
                                coroutineScope {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(
                                            requireUnconsumed = false,
                                            pass = PointerEventPass.Initial
                                        )
                                        var started = false
                                        var stillDown = true
                                        var canceled = false
                                        val timer = launch {
                                            delay(300)
                                            if (stillDown) {
                                                started = true
                                                beginRecording()
                                            }
                                        }
                                        while (true) {
                                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                                            val c = ev.changes.firstOrNull() ?: break
                                            if (!c.pressed) {
                                                stillDown = false
                                                break
                                            }
                                            if (started) {
                                                val dy = c.position.y - down.position.y
                                                if (dy < -60f && !canceled) {
                                                    canceled = true
                                                    cancelHint = true
                                                    onRecordUi(true, true, recorder.level)
                                                } else if (dy > -30f && canceled) {
                                                    canceled = false
                                                    cancelHint = false
                                                    onRecordUi(true, false, recorder.level)
                                                }
                                            }
                                        }
                                        timer.cancel()
                                        if (started) finishRecording(canceled)
                                    }
                                }
                            }
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip(
                    label = "深度思考",
                    active = thinking,
                    onClick = onToggleThinking,
                    icon = painterResource(R.drawable.ic_official_think)
                )
                Spacer(Modifier.width(6.dp))
                Chip(
                    label = "智能搜索",
                    active = web,
                    onClick = onToggleWeb,
                    icon = painterResource(R.drawable.ic_official_globe)
                )
                Spacer(Modifier.weight(1f))

                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable {
                            picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp)
                    else PlusCircleIcon(tint = cs.onSurface, size = 21.dp)
                }

                Spacer(Modifier.width(2.dp))

                if (streaming) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(cs.onSurface.copy(alpha = 0.08f))
                            .clickable { onStop() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Stop, "停止", Modifier.size(14.dp), tint = cs.onSurface)
                    }
                } else if (ready) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(cs.primary)
                            .clickable { fire() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.ArrowUpward, "发送",
                            Modifier.size(17.dp), tint = Color.White
                        )
                    }
                } else {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .clickable {
                                if (voiceMode) {
                                    voiceMode = false
                                    focused = true
                                } else {
                                    focused = false
                                    runCatching { keyboard?.hide() }
                                    voiceMode = true
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (voiceMode) {
                            Icon(
                                Icons.Rounded.Keyboard, "切换到键盘",
                                Modifier.size(19.dp), tint = cs.onSurface
                            )
                        } else {
                            Icon(
                                painterResource(R.drawable.ic_official_voice), "按住说话",
                                Modifier.size(20.dp), tint = cs.onSurface
                            )
                        }
                    }
                }
            }
        }

        Text(
            "回答由 AI 生成，仅供参考",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
        )
    }
}

/** 官方胶囊：未选中描边，选中浅蓝底蓝字 */
@Composable
fun Chip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.painter.Painter? = null
) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(
        if (active) cs.primaryContainer else Color.Transparent, label = "chipBg"
    )
    val fg by animateColorAsState(
        if (active) cs.primary else cs.onSurface, label = "chipFg"
    )
    val borderColor by animateColorAsState(
        if (active) Color.Transparent else cs.outline, label = "chipBorder"
    )
    Row(
        Modifier
            .height(33.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(17.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(15.dp), tint = fg)
            Spacer(Modifier.width(5.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}

/** 官方"深度思考"图标：两个交叉的圆角框 */
@Composable
fun ThinkIcon(tint: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = s * 0.1f
        val r = CornerRadius(s * 0.16f)
        val w = s * 0.78f
        val h = s * 0.5f
        drawRoundRect(
            color = tint,
            topLeft = Offset((s - w) / 2, (s - h) / 2),
            size = Size(w, h),
            cornerRadius = r,
            style = Stroke(stroke)
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset((s - h) / 2 - s * 0.06f, (s - w) / 2),
            size = Size(h, w),
            cornerRadius = r,
            style = Stroke(stroke)
        )
    }
}

/** 圆里一个加号，手绘保证线宽一致 */
@Composable
fun PlusCircleIcon(tint: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val stroke = this.size.minDimension * 0.062f
        drawCircle(color = tint, radius = r - stroke, style = Stroke(stroke))
        val arm = r * 0.48f
        drawLine(
            tint, Offset(center.x - arm, center.y), Offset(center.x + arm, center.y),
            stroke, StrokeCap.Round
        )
        drawLine(
            tint, Offset(center.x, center.y - arm), Offset(center.x, center.y + arm),
            stroke, StrokeCap.Round
        )
    }
}

/** 读取相册图片并压缩，长边不超过 1600 */
fun savePicked(ctx: android.content.Context, uri: android.net.Uri): String? = runCatching {
    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    var sample = 1
    val maxSide = maxOf(opts.outWidth, opts.outHeight)
    while (maxSide / sample > 1600) sample *= 2

    val bmp = BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample }
    ) ?: return null
    val out = ByteArrayOutputStream()
    var quality = 88
    bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
    while (out.size() > 1_400_000 && quality > 40) {
        out.reset()
        quality -= 12
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
    }
    bmp.recycle()
    val name = "img_${System.currentTimeMillis()}_${(0..999).random()}.jpg"
    File(Store.imageDir(ctx), name).writeBytes(out.toByteArray())
    name
}.getOrNull()
