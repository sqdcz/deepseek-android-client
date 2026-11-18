package com.shipyard.deepchat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shipyard.deepchat.ui.theme.Mono

sealed interface MdBlock {
    data class P(val text: String) : MdBlock
    data class H(val level: Int, val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Quote(val lines: List<String>) : MdBlock
    data class Bullet(val indent: Int, val marker: String, val text: String) : MdBlock
    data object Rule : MdBlock
}

fun parseMarkdown(md: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = md.replace("\r\n", "\n").split("\n")
    var i = 0
    val para = StringBuilder()

    fun flush() {
        if (para.isNotBlank()) {
            out.add(MdBlock.P(para.toString().trim()))
            para.clear()
        } else para.clear()
    }

    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimEnd()
        val t = line.trim()

        // 代码块
        if (t.startsWith("```")) {
            flush()
            val lang = t.removePrefix("```").trim()
            val sb = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                sb.append(lines[i]).append('\n')
                i++
            }
            if (i < lines.size) i++
            out.add(MdBlock.Code(lang, sb.toString().trimEnd('\n')))
            continue
        }
        // 标题
        val h = Regex("^(#{1,6})\\s+(.*)$").find(t)
        if (h != null) {
            flush()
            out.add(MdBlock.H(h.groupValues[1].length, h.groupValues[2].trim()))
            i++
            continue
        }
        // 分隔线
        if (Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(t)) {
            flush()
            out.add(MdBlock.Rule)
            i++
            continue
        }
        // 引用
        if (t.startsWith(">")) {
            flush()
            val qs = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                qs.add(lines[i].trim().removePrefix(">").trim())
                i++
            }
            out.add(MdBlock.Quote(qs))
            continue
        }
        // 列表
        val b = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$").find(line)
        if (b != null) {
            flush()
            val indent = b.groupValues[1].length / 2
            val marker = b.groupValues[2]
            val bullet = if (marker[0].isDigit()) marker.removeSuffix(".").removeSuffix(")") + "." else "•"
            out.add(MdBlock.Bullet(indent, bullet, b.groupValues[3]))
            i++
            continue
        }
        if (t.isEmpty()) {
            flush()
            i++
            continue
        }
        if (para.isNotEmpty()) para.append('\n')
        para.append(t)
        i++
    }
    flush()
    return out
}

private val inlineRegex = Regex(
    "`([^`]+)`" +
        "|\\*\\*(.+?)\\*\\*" +
        "|__(.+?)__" +
        "|~~(.+?)~~" +
        "|\\*(?!\\s)(.+?)\\*" +
        "|\\[([^\\]]+)]\\(([^)\\s]+)\\)"
)

@Composable
fun inlineMd(text: String, base: Color = MaterialTheme.colorScheme.onSurface): AnnotatedString {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val link = MaterialTheme.colorScheme.primary
    return remember(text, base, codeBg, link) {
        buildAnnotatedString {
            var last = 0
            for (m in inlineRegex.findAll(text)) {
                if (m.range.first > last) append(text.substring(last, m.range.first))
                val g = m.groupValues
                when {
                    g[1].isNotEmpty() -> withStyle(
                        SpanStyle(fontFamily = Mono, background = codeBg, color = base)
                    ) { append(" " + g[1] + " ") }

                    g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(g[2])
                    }

                    g[3].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(g[3])
                    }

                    g[4].isNotEmpty() -> withStyle(
                        SpanStyle(textDecoration = TextDecoration.LineThrough)
                    ) { append(g[4]) }

                    g[5].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(g[5])
                    }

                    g[6].isNotEmpty() -> {
                        pushStringAnnotation("url", g[7])
                        withStyle(
                            SpanStyle(color = link, textDecoration = TextDecoration.Underline)
                        ) { append(g[6]) }
                        pop()
                    }

                    else -> append(m.value)
                }
                last = m.range.last + 1
            }
            if (last < text.length) append(text.substring(last))
        }
    }
}

@Composable
fun MarkdownText(
    md: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    val blocks = remember(md) { parseMarkdown(md) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in blocks) {
            when (b) {
                is MdBlock.P -> Text(
                    text = inlineMd(b.text, color),
                    style = MaterialTheme.typography.bodyLarge,
                    color = color
                )

                is MdBlock.H -> Text(
                    text = inlineMd(b.text, color),
                    style = when (b.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    color = color,
                    modifier = Modifier.padding(top = 4.dp)
                )

                is MdBlock.Code -> CodeBlock(b.lang, b.code)

                is MdBlock.Quote -> Row(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height((b.lines.size * 22).dp)
                            .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        for (l in b.lines) Text(
                            inlineMd(l, MaterialTheme.colorScheme.onSurfaceVariant),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is MdBlock.Bullet -> Row(Modifier.padding(start = (b.indent * 14).dp)) {
                    Text(
                        b.marker,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(20.dp)
                    )
                    Text(
                        inlineMd(b.text, color),
                        style = MaterialTheme.typography.bodyLarge,
                        color = color
                    )
                }

                MdBlock.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

private val keywords = listOf(
    "fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while",
    "return", "import", "package", "try", "catch", "finally", "throw", "null", "true", "false",
    "def", "elif", "lambda", "pass", "raise", "with", "as", "from", "async", "await", "yield",
    "function", "const", "let", "new", "this", "export", "default", "extends", "typeof",
    "public", "private", "protected", "static", "void", "int", "float", "double", "String",
    "struct", "impl", "fn", "mut", "use", "pub", "match", "module", "namespace", "using"
)

private val tokenRegex = Regex(
    "(//[^\\n]*|/\\*[\\s\\S]*?\\*/|#[^\\n]*)" +
        "|(\"(?:[^\"\\\\\\n]|\\\\.)*\"|'(?:[^'\\\\\\n]|\\\\.)*')" +
        "|\\b(\\d+(?:\\.\\d+)?)\\b" +
        "|\\b(" + keywords.joinToString("|") + ")\\b"
)

@Composable
fun CodeBlock(lang: String, code: String) {
    val cs = MaterialTheme.colorScheme
    val clip = LocalClipboardManager.current
    val highlighted = remember(code, cs.primary, cs.onSurface) {
        buildAnnotatedString {
            var last = 0
            for (m in tokenRegex.findAll(code)) {
                if (m.range.first > last) append(code.substring(last, m.range.first))
                val g = m.groupValues
                when {
                    g[1].isNotEmpty() -> withStyle(
                        SpanStyle(color = cs.onSurfaceVariant.copy(alpha = 0.7f))
                    ) { append(g[1]) }

                    g[2].isNotEmpty() -> withStyle(SpanStyle(color = Color(0xFF2E9E6B))) {
                        append(g[2])
                    }

                    g[3].isNotEmpty() -> withStyle(SpanStyle(color = Color(0xFFC77D22))) {
                        append(g[3])
                    }

                    g[4].isNotEmpty() -> withStyle(
                        SpanStyle(color = cs.primary, fontWeight = FontWeight.Medium)
                    ) { append(g[4]) }

                    else -> append(m.value)
                }
                last = m.range.last + 1
            }
            if (last < code.length) append(code.substring(last))
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(cs.surfaceVariant, RoundedCornerShape(12.dp))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                lang.ifBlank { "code" }.lowercase(),
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            androidx.compose.material3.IconButton(
                onClick = { clip.setText(AnnotatedString(code)) },
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = "复制代码",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = 0.dp)
        ) {
            Text(
                text = highlighted,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = Mono,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                ),
                softWrap = false
            )
        }
    }
}
