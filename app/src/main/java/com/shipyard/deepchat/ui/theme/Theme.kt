package com.shipyard.deepchat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 取自官方包截图的实测色值 */
val Brand = Color(0xFF4D6BFE)
val BrandDark = Color(0xFF6E8BFF)

private val LightScheme = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFECF2FE),   // 用户气泡 / 搜索胶囊选中底
    onPrimaryContainer = Color(0xFF0F0F0F), // 气泡里是黑字
    secondary = Color(0xFF8A8F98),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3F4F6), // 搜索框底
    onSecondaryContainer = Color(0xFF0F0F0F),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0F0F0F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F0F0F),
    surfaceVariant = Color(0xFFF5F6F8),     // 设置页背景 / 代码块底
    onSurfaceVariant = Color(0xFF8A8F98),
    outline = Color(0xFFE8E9EB),            // 描边 / 分隔线
    outlineVariant = Color(0xFFF0F1F3),
    error = Color(0xFFE5484D),
    surfaceTint = Color.Transparent
)

private val DarkScheme = darkColorScheme(
    primary = BrandDark,
    onPrimary = Color(0xFF0A1024),
    primaryContainer = Color(0xFF283142),   // 深色搜索胶囊底
    onPrimaryContainer = Color(0xFFE9EBF2),
    secondary = Color(0xFF8E9299),
    onSecondary = Color(0xFF0F0F0F),
    secondaryContainer = Color(0xFF242424),
    onSecondaryContainer = Color(0xFFF2F3F5),
    background = Color(0xFF0F0F0F),
    onBackground = Color(0xFFF2F3F5),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFF2F3F5),
    surfaceVariant = Color(0xFF242424),     // 深色输入卡片
    onSurfaceVariant = Color(0xFF8E9299),
    outline = Color(0xFF2E2E2E),
    outlineVariant = Color(0xFF232323),
    error = Color(0xFFFF6B6B),
    surfaceTint = Color.Transparent
)

@Composable
fun DeepChatTheme(
    theme: String = "system",
    fontScale: Float = 1f,
    content: @Composable () -> Unit
) {
    val dark = when (theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val scheme = if (dark) DarkScheme else LightScheme
    val fs = 16f * fontScale
    val typography = Typography(
        headlineSmall = TextStyle(fontSize = (fs + 2).sp, lineHeight = (fs + 8).sp, fontWeight = FontWeight.Normal),
        titleLarge = TextStyle(fontSize = (fs + 2).sp, lineHeight = (fs + 8).sp, fontWeight = FontWeight.Medium),
        titleMedium = TextStyle(fontSize = (fs + 1).sp, lineHeight = (fs + 6).sp, fontWeight = FontWeight.Medium),
        titleSmall = TextStyle(fontSize = fs.sp, lineHeight = (fs + 4).sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontSize = fs.sp, lineHeight = (fs * 1.62f).sp),
        bodyMedium = TextStyle(fontSize = (fs - 1).sp, lineHeight = (fs * 1.55f).sp),
        bodySmall = TextStyle(fontSize = (fs - 3).sp, lineHeight = (fs * 1.45f).sp),
        labelLarge = TextStyle(fontSize = (fs - 1).sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontSize = (fs - 3).sp, lineHeight = (fs * 1.3f).sp),
        labelSmall = TextStyle(fontSize = (fs - 4).sp, lineHeight = (fs * 1.3f).sp)
    )
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}

val Mono = FontFamily.Monospace

/** 当前是否深色：输入卡片、设置页背景要按这个切换 */
@Composable
fun isDarkTheme(): Boolean =
    MaterialTheme.colorScheme.background.luminance() < 0.5f
