package com.flexhealth.heartmonitor.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

val HeartRed = Color(0xFFFF3B5C)
val GlassWhite = Color(0xCCFFFFFF)
val StatusOk = Color(0xFF3DDC97)
val StatusWarn = Color(0xFFFFC145)

private val scheme = ColorScheme(
    primary = HeartRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF5A1422),
    onPrimaryContainer = Color(0xFFFFD9DF),
    background = Color.Black,
    onBackground = Color.White,
)

@Composable
fun FlexWearTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)
