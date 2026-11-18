package com.shipyard.deepchat.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.shipyard.deepchat.R
import com.shipyard.deepchat.data.Citation
import com.shipyard.deepchat.data.Msg
import com.shipyard.deepchat.data.Store
import com.shipyard.deepchat.data.ToolTrace
import com.shipyard.deepchat.ui.theme.Mono

@Composable
fun MessageItem(
    msg: Msg,
    streaming: Boolean,
    isLast: Boolean,
    showReasoning: Boolean = true,
    onRegenerate: () -> Unit,
    onEdit: (String) -> Unit,
    onSpeak: (String) -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    if (msg.isUser) {
        var menu by remember { mutableStateOf(false) }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 56.dp, end = 14.dp, top = 12.dp, bottom = 2.dp),
            horizontalAlignment = Alignment.End
        ) {
            if (msg.images.isNotEmpty()) {
                ImageStrip(msg.images)
                Spacer(Modifier.height(6.dp))
            }
            Box {
                Box(
                    Modifier
                        .widthIn(max = 292.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(cs.primaryContainer)
                        .clickable { menu = true }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = msg.content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = cs.onPrimaryContainer
                        )
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val clip = LocalClipboardManager.current
                    DropdownMenuItem(
                        text = { Text("复制") },
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                        onClick = { clip.setText(AnnotatedString(msg.content)); menu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("修改") },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = { menu = false; onEdit(msg.content) }
                    )
                }
            }
        }
        return
    }

    // 助手消息：不加气泡，整行排版
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp)
    ) {
        if (msg.reasoning.isNotBlank() && showReasoning) {
            ReasoningBlock(msg.reasoning, streaming && msg.content.isBlank(), msg.durationMs)
        }
        if (msg.tools.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            ToolTraceRow(msg.tools)
        }

        if (msg.content.isNotBlank()) {
            Spacer(
                Modifier.height(
                    if (msg.reasoning.isNotBlank() || msg.tools.isNotEmpty()) 10.dp else 0.dp
                )
            )
            SelectionContainer {
                MarkdownText(
                    md = msg.content,
                    color = cs.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (streaming && isLast) BlinkingCursor()
        } else if (streaming && isLast && msg.reasoning.isBlank() && msg.tools.isEmpty()) {
            ThinkingDots()
        }

        msg.error?.let { err ->
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(cs.error.copy(alpha = 0.07f))
                    .padding(10.dp)
            ) {
                Text(err, style = MaterialTheme.typography.bodySmall, color = cs.error)
            }
        }

        if (msg.citations.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            CitationChip(msg.citations)
        }

        if (!streaming && (msg.content.isNotBlank() || msg.error != null)) {
            Spacer(Modifier.height(10.dp))
            ActionRow(msg = msg, onRegenerate = onRegenerate, onSpeak = onSpeak)
            Text(
                "本回答由 AI 生成，内容仅供参考，请仔细甄别",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

/** 官方底部那排灰图标：复制 / 朗读 / 分享 / 重新生成 */
@Composable
private fun ActionRow(
    msg: Msg,
    onRegenerate: () -> Unit,
    onSpeak: (String) -> Unit
) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconAction(painterResource(R.drawable.ic_official_copy), "复制") {
            clip.setText(AnnotatedString(msg.content))
        }
        Spacer(Modifier.width(20.dp))
        IconAction(painterResource(R.drawable.ic_official_voice), "朗读") { onSpeak(msg.content) }
        Spacer(Modifier.width(20.dp))
        IconAction(painterResource(R.drawable.ic_official_share), "分享") {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, msg.content)
            }
            ctx.startActivity(Intent.createChooser(i, "分享回答"))
        }
        Spacer(Modifier.width(20.dp))
        IconAction(painterResource(R.drawable.ic_official_redo), "重新生成", onRegenerate)
    }
}

@Composable
private fun IconAction(
    painter: androidx.compose.ui.graphics.painter.Painter,
    label: String,
    onClick: () -> Unit
) {
    Icon(
        painter, label,
        modifier = Modifier
            .size(20.dp)
            .clickable(onClick = onClick),
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
    )
}

/** "已深度思考（用时 x 秒）" 一行灰字，点开是浅灰块 */
@Composable
fun ReasoningBlock(reasoning: String, streaming: Boolean, durationMs: Long) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(streaming) }
    LaunchedEffect(streaming) { if (!streaming) expanded = false }

    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (streaming) {
                CircularProgressIndicator(
                    Modifier.size(10.dp), strokeWidth = 1.3.dp, color = cs.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "正在思考…",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            } else {
                Text(
                    "已深度思考" + if (durationMs > 0) "（用时 ${fmtDuration(durationMs)}）" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    null,
                    modifier = Modifier.size(15.dp),
                    tint = cs.onSurfaceVariant
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            SelectionContainer {
                Text(
                    text = reasoning,
                    style = MaterialTheme.typography.bodySmall.copy(
                        lineHeight = MaterialTheme.typography.bodySmall.fontSize * 1.75f
                    ),
                    color = cs.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(cs.surfaceVariant.copy(alpha = 0.7f))
                        .padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun ToolTraceRow(tools: List<ToolTrace>) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (t in tools) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (t.status == "running") {
                    CircularProgressIndicator(
                        Modifier.size(10.dp), strokeWidth = 1.2.dp, color = cs.primary
                    )
                } else {
                    Icon(
                        if (t.kind.contains("link")) Icons.Rounded.Link else Icons.Rounded.Language,
                        null, Modifier.size(12.dp), tint = cs.primary
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    t.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.primary.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 引用来源做成官方那种描边胶囊："N 个网页" */
@Composable
private fun CitationChip(citations: List<Citation>) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, cs.outline, RoundedCornerShape(18.dp))
                .clickable { open = !open }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Language, null,
                Modifier.size(14.dp), tint = cs.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "${citations.size} 个网页",
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurface
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                null, Modifier.size(14.dp), tint = cs.onSurfaceVariant
            )
        }
        AnimatedVisibility(visible = open, enter = expandVertically(), exit = shrinkVertically()) {
            Column(Modifier.padding(top = 6.dp)) {
                for ((i, c) in citations.withIndex()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(c.url))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                            .padding(vertical = 6.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${i + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.primary,
                            modifier = Modifier.width(18.dp)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                c.title.ifBlank { c.url },
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (c.site.isNotBlank()) Text(
                                c.site,
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageStrip(names: List<String>) {
    val ctx = LocalContext.current
    var preview by remember { mutableStateOf<String?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (n in names) {
            AsyncImage(
                model = Store.imageFile(ctx, n),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(if (names.size == 1) 170.dp else 90.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { preview = n }
            )
        }
    }
    preview?.let { n ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { preview = null }) {
            AsyncImage(
                model = Store.imageFile(ctx, n),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { preview = null },
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun BlinkingCursor() {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(620), RepeatMode.Reverse),
        label = "cursorAlpha"
    )
    Text(
        "▍",
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(start = 1.dp)
    )
}

@Composable
private fun ThinkingDots() {
    val transition = rememberInfiniteTransition(label = "dots")
    val p by transition.animateFloat(
        initialValue = 0f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Restart),
        label = "dotsP"
    )
    val dots = ".".repeat((p.toInt() % 3) + 1)
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            Modifier.size(11.dp), strokeWidth = 1.4.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(7.dp))
        Text(
            "正在输入$dots",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

fun fmtDuration(ms: Long): String {
    val s = ms / 1000.0
    return if (s < 60) String.format("%.0fs", s) else "${(s / 60).toInt()}m${(s % 60).toInt()}s"
}
