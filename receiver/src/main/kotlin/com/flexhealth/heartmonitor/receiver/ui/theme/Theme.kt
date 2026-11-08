package com.flexhealth.heartmonitor.receiver.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Paleta Liquid Glass (inspirada en Flex OS Ultra): fondo profundo, acentos rojo corazón. */
object Flex {
    val Heart = Color(0xFFFF3B5C)
    val HeartSoft = Color(0xFFFF7A90)
    val Violet = Color(0xFF7B5CFF)
    val Teal = Color(0xFF2EC4B6)
    val Ok = Color(0xFF3DDC97)
    val Warn = Color(0xFFFFC145)
    val Bad = Color(0xFFFF4D67)
    val Ink = Color(0xFF06070B)
    val Ink2 = Color(0xFF0E1018)
    val TextHigh = Color(0xFFF5F6FA)
    val TextMid = Color(0xB3F5F6FA)
    val TextLow = Color(0x80F5F6FA)
}

private val colors = darkColorScheme(
    primary = Flex.Heart,
    onPrimary = Color.White,
    secondary = Flex.Violet,
    tertiary = Flex.Teal,
    background = Flex.Ink,
    surface = Color(0x14FFFFFF),
    onSurface = Flex.TextHigh,
    onBackground = Flex.TextHigh,
    surfaceVariant = Color(0x1FFFFFFF),
    onSurfaceVariant = Flex.TextMid,
    outline = Color(0x33FFFFFF),
    error = Flex.Bad,
)

private val type = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 88.sp, letterSpacing = (-2).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.3.sp),
)

@Composable
fun FlexTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, typography = type, content = content)
