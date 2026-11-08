package com.flexhealth.heartmonitor.receiver.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flexhealth.heartmonitor.receiver.data.OverlayPrefs
import com.flexhealth.heartmonitor.receiver.overlay.OverlayLimits
import com.flexhealth.heartmonitor.receiver.overlay.OverlaySize
import com.flexhealth.heartmonitor.receiver.ui.components.GlassButton
import com.flexhealth.heartmonitor.receiver.ui.components.GlassCard
import com.flexhealth.heartmonitor.receiver.ui.components.LabeledSlider
import com.flexhealth.heartmonitor.receiver.ui.components.SectionTitle
import com.flexhealth.heartmonitor.receiver.ui.components.Segmented
import com.flexhealth.heartmonitor.receiver.ui.components.ToggleRow
import com.flexhealth.heartmonitor.receiver.ui.components.VSpace
import com.flexhealth.heartmonitor.receiver.ui.theme.Flex

@Composable
fun OverlayScreen(p: OverlayPrefs, canDraw: Boolean, running: Boolean, onUpdate: ((OverlayPrefs) -> OverlayPrefs) -> Unit, onGrant: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Overlay flotante") {}
            if (!canDraw) {
                Text("Necesita el permiso «Mostrar sobre otras apps» para superponerse a juegos como Geometry Dash.",
                    style = MaterialTheme.typography.bodyMedium, color = Flex.Warn)
                VSpace(8)
                GlassButton("Conceder permiso", onGrant, Modifier.fillMaxWidth(), accent = Flex.Heart)
                VSpace(8)
            }
            ToggleRow("Mostrar overlay", if (running) "Visible mientras la monitorización está activa" else "Se mostrará al iniciar la monitorización",
                p.enabled, { v -> onUpdate { it.copy(enabled = v) } }, enabled = canDraw)
            ToggleRow("Modo juego", "Solo corazón, BPM, mini gráfica y estado; los toques atraviesan el widget",
                p.gameMode, { v -> onUpdate { it.copy(gameMode = v, clickThrough = v) } })
            ToggleRow("Toques a través del widget", "El juego recibe los toques sobre el widget. Para moverlo, desactívalo aquí o desde la notificación",
                p.clickThrough, { v -> onUpdate { it.copy(clickThrough = v) } })
            ToggleRow("Bloquear posición", null, p.locked, { v -> onUpdate { it.copy(locked = v) } })
            ToggleRow("Mini gráfica", null, p.showGraph, { v -> onUpdate { it.copy(showGraph = v) } })
            ToggleRow("Minimizado", "Solo corazón y BPM", p.minimized, { v -> onUpdate { it.copy(minimized = v) } })
            ToggleRow("Animaciones", "Desactívalas para el mínimo consumo de GPU", p.animations, { v -> onUpdate { it.copy(animations = v) } })
        }
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Tamaño") {}
            Segmented(OverlaySize.entries.toList(), p.size, { when (it) { OverlaySize.COMPACT -> "190×65"; OverlaySize.NORMAL -> "230×76"; OverlaySize.LARGE -> "280×100"; OverlaySize.CUSTOM -> "Personal." } },
                { s -> onUpdate { it.copy(size = s, minimized = false) } }, Modifier.fillMaxWidth())
            VSpace(10)
            var w by remember(p.customWidthDp) { mutableFloatStateOf(p.customWidthDp.toFloat()) }
            var h by remember(p.customHeightDp) { mutableFloatStateOf(p.customHeightDp.toFloat()) }
            LabeledSlider("Ancho", w, OverlayLimits.MIN_W_DP.toFloat()..OverlayLimits.MAX_W_DP.toFloat(), "${w.toInt()} dp", { w = it },
                { onUpdate { it.copy(size = OverlaySize.CUSTOM, customWidthDp = w.toInt()) } })
            LabeledSlider("Alto", h, OverlayLimits.MIN_H_DP.toFloat()..OverlayLimits.MAX_H_DP.toFloat(), "${h.toInt()} dp", { h = it },
                { onUpdate { it.copy(size = OverlaySize.CUSTOM, customHeightDp = h.toInt()) } })
            Text("También puedes redimensionar desde bordes y esquinas: mantén pulsado el widget › Redimensionar.",
                style = MaterialTheme.typography.bodyMedium, color = Flex.TextLow)
        }
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Apariencia") {}
            var a by remember(p.alpha) { mutableFloatStateOf(p.alpha) }
            LabeledSlider("Opacidad del cristal", a, OverlayLimits.MIN_ALPHA..OverlayLimits.MAX_ALPHA, "${(a * 100).toInt()} %", { a = it },
                { onUpdate { it.copy(alpha = a) } })
            VSpace(6)
            GlassButton("Restablecer posición", { onUpdate { it.copy(portraitX = 1f, portraitY = 0.06f, landscapeX = null, landscapeY = null) } }, Modifier.fillMaxWidth())
        }
    }
}
