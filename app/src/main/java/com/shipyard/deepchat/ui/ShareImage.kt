package com.shipyard.deepchat.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.shipyard.deepchat.data.Conversation
import com.shipyard.deepchat.data.Msg
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 把一整段对话画成一张竖版长图，用系统 StaticLayout 排版，避免依赖 Compose 截图 */
object ShareImage {

    private const val W = 1000
    private const val PAD = 52
    private const val MAX_H = 16000

    private class Block(
        val layout: StaticLayout,
        val bg: Int?,
        val padH: Int,
        val padV: Int,
        val gap: Int,
        val label: String? = null,
        val labelPaint: TextPaint? = null
    ) {
        val height: Int get() = layout.height + padV * 2 + (if (label != null) 46 else 0) + gap
    }

    fun export(ctx: Context, conv: Conversation): File? {
        val contentW = W - PAD * 2

        val titlePaint = TextPaint().apply {
            isAntiAlias = true
            textSize = 48f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            color = 0xFF14161A.toInt()
        }
        val metaPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = 26f
            color = 0xFF8A919E.toInt()
        }
        val bodyPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = 32f
            color = 0xFF1B1E24.toInt()
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val thinkPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = 26f
            color = 0xFF7A8290.toInt()
        }
        val labelPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = 26f
            color = 0xFF6B7280.toInt()
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        fun lay(text: String, p: TextPaint, width: Int): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, p, width)
                .setLineSpacing(0f, 1.32f)
                .setIncludePad(false)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()

        val blocks = mutableListOf<Block>()
        blocks.add(
            Block(
                layout = lay(conv.title, titlePaint, contentW),
                bg = null, padH = 0, padV = 0, gap = 10
            )
        )
        blocks.add(
            Block(
                layout = lay(
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        .format(Date(conv.createdAt)) + "  ·  DeepChat",
                    metaPaint, contentW
                ),
                bg = null, padH = 0, padV = 0, gap = 26
            )
        )

        for (m: Msg in conv.messages) {
            if (m.isUser) {
                blocks.add(
                    Block(
                        layout = lay(m.content.ifBlank { "[图片]" }, bodyPaint, contentW - 40),
                        bg = 0xFFEFF2FF.toInt(),
                        padH = 20, padV = 16, gap = 18,
                        label = "我", labelPaint = labelPaint
                    )
                )
            } else {
                if (m.reasoning.isNotBlank()) {
                    blocks.add(
                        Block(
                            layout = lay(m.reasoning, thinkPaint, contentW - 24),
                            bg = 0xFFF5F6F8.toInt(),
                            padH = 12, padV = 12, gap = 12,
                            label = "思考过程", labelPaint = labelPaint
                        )
                    )
                }
                if (m.content.isNotBlank()) {
                    blocks.add(
                        Block(
                            layout = lay(m.content, bodyPaint, contentW),
                            bg = null, padH = 0, padV = 0, gap = 18,
                            label = "DeepChat", labelPaint = labelPaint
                        )
                    )
                }
                if (m.citations.isNotEmpty()) {
                    val txt = m.citations.mapIndexed { i, c -> "${i + 1}. ${c.title.ifBlank { c.url }}" }
                        .joinToString("\n")
                    blocks.add(
                        Block(
                            layout = lay(txt, metaPaint, contentW - 24),
                            bg = null, padH = 12, padV = 8, gap = 14
                        )
                    )
                }
            }
        }

        val footer = lay("由 DeepChat 导出", metaPaint, contentW)
        val totalH = PAD + blocks.sumOf { it.height } + footer.height + PAD + 30
        val H = minOf(totalH, MAX_H)

        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        var y = PAD
        for (b in blocks) {
            if (y > H - 60) break
            val bh = b.height
            if (b.bg != null) {
                val p = Paint().apply {
                    color = b.bg
                    isAntiAlias = true
                }
                canvas.drawRoundRect(
                    PAD.toFloat(), y.toFloat(),
                    (W - PAD).toFloat(), (y + bh).toFloat(),
                    18f, 18f, p
                )
            }
            var ty = y
            b.label?.let { l ->
                b.labelPaint?.let { lp -> canvas.drawText(l, (PAD + b.padH).toFloat(), ty + 24f, lp) }
                ty += 40
            }
            canvas.save()
            canvas.translate((PAD + b.padH).toFloat(), (ty + b.padV).toFloat())
            b.layout.draw(canvas)
            canvas.restore()
            y += bh
        }
        y += 16
        if (y < H - 20) {
            canvas.save()
            canvas.translate(PAD.toFloat(), y.toFloat())
            footer.draw(canvas)
            canvas.restore()
        }

        val f = File(ctx.filesDir, "export/deepchat_${System.currentTimeMillis()}.png")
        f.parentFile?.mkdirs()
        return runCatching {
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            f
        }.getOrNull()
    }

    fun share(ctx: Context, f: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, ctx.packageName + ".fileprovider", f
        )
        val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(
            android.content.Intent.createChooser(i, "分享长图")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
