package com.flexhealth.heartmonitor.receiver.ui.glass

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.flexhealth.heartmonitor.receiver.ui.theme.Flex

/**
 * Fondo de la app. Es estático (sin animación continua, para no gastar batería ni GPU) y se
 * comparte con las superficies de vidrio, que lo vuelven a dibujar desenfocado bajo sí mismas:
 * así el desenfoque es real respecto al fondo, sin capturas de pantalla.
 */
@Stable
class Backdrop {
    var rootSize by mutableStateOf(Size.Zero)

    fun DrawScope.paint(size: Size) {
        drawRect(Brush.verticalGradient(listOf(Flex.Ink, Flex.Ink2, Flex.Ink), startY = 0f, endY = size.height))
        blob(Flex.Heart.copy(alpha = 0.42f), Offset(size.width * 0.12f, size.height * 0.10f), size.minDimension * 0.65f)
        blob(Flex.Violet.copy(alpha = 0.30f), Offset(size.width * 0.95f, size.height * 0.42f), size.minDimension * 0.70f)
        blob(Flex.Teal.copy(alpha = 0.16f), Offset(size.width * 0.25f, size.height * 0.92f), size.minDimension * 0.60f)
        blob(Flex.HeartSoft.copy(alpha = 0.14f), Offset(size.width * 0.70f, size.height * 0.80f), size.minDimension * 0.40f)
    }

    private fun DrawScope.blob(c: Color, center: Offset, radius: Float) =
        drawCircle(Brush.radialGradient(listOf(c, c.copy(alpha = 0f)), center, radius), radius, center)
}

val LocalBackdrop = staticCompositionLocalOf { Backdrop() }

@Composable
fun LiquidBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val backdrop = remember { Backdrop() }
    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { backdrop.rootSize = it.toSize() }
            .drawBehind { with(backdrop) { paint(size) } },
    ) {
        CompositionLocalProvider(LocalBackdrop provides backdrop) { content() }
    }
}

/**
 * Superficie Liquid Glass: fondo desenfocado (RenderEffect, Android 12+), tinte translúcido,
 * reflejo superior y borde degradado. En Android 11 se usa solo el tinte (sin desenfoque).
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    blur: Dp = 28.dp,
    tint: Float = 0.08f,
    elevation: Dp = 10.dp,
    /** Velo opaco opcional bajo el tinte (legibilidad de barras sobre contenido desplazable). */
    scrim: Color = Color.Transparent,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = LocalBackdrop.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .shadow(elevation, shape, ambientColor = Color.Black, spotColor = Color.Black.copy(alpha = 0.5f))
            .clip(shape)
            .onGloballyPositioned { origin = it.positionInRoot() },
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val r = blur.toPx()
                        renderEffect = BlurEffect(r, r, TileMode.Clamp)
                        clip = true
                    }
                    .drawBehind {
                        val o = origin
                        translate(-o.x, -o.y) { with(backdrop) { paint(backdrop.rootSize) } }
                    },
            )
        }
        if (scrim != Color.Transparent) Box(Modifier.matchParentSize().background(scrim))
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = tint + if (Build.VERSION.SDK_INT >= 31) 0.04f else 0.08f),
                            Color.White.copy(alpha = tint * 0.4f),
                        ),
                    ),
                )
                .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.38f), Color.White.copy(alpha = 0.06f))), shape),
        )
        content()
    }
}
