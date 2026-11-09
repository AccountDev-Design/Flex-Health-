package com.flexhealth.heartmonitor.receiver.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flexhealth.heartmonitor.engine.Freshness
import com.flexhealth.heartmonitor.engine.MonitorSnapshot
import com.flexhealth.heartmonitor.protocol.LinkState
import com.flexhealth.heartmonitor.protocol.LinkStatus
import com.flexhealth.heartmonitor.protocol.ReadingQuality
import com.flexhealth.heartmonitor.protocol.SensorStatus
import com.flexhealth.heartmonitor.receiver.ui.glass.GlassSurface
import com.flexhealth.heartmonitor.receiver.ui.theme.Flex

/** Tarjeta de vidrio estándar con padding. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    GlassSurface(modifier) { Column(Modifier.padding(20.dp)) { content() } }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = Flex.TextHigh, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** BPM principal con corazón que late una vez por cada muestra real recibida. */
@Composable
fun BpmHero(s: MonitorSnapshot, modifier: Modifier = Modifier) {
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(s.historyVersion) {
        if (s.freshness == Freshness.LIVE && s.stats.samples > 0) {
            pulse.snapTo(1.22f); pulse.animateTo(1f, tween(420))
        }
    }
    val bpm = s.displayBpm
    val color by animateColorAsState(
        when {
            bpm != null && !s.reliable -> Flex.Warn // lectura real pero marcada como no fiable
            s.freshness == Freshness.LIVE -> Flex.TextHigh
            s.freshness == Freshness.STALE -> Flex.TextMid
            else -> Flex.TextLow
        }, label = "bpm",
    )
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(64.dp).scale(pulse.value)
                .background(Brush.radialGradient(listOf(Flex.Heart.copy(alpha = 0.45f), Color.Transparent)), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Favorite, contentDescription = null, tint = Flex.Heart, modifier = Modifier.size(38.dp)) }
        Spacer(Modifier.width(14.dp))
        Text(bpm?.toString() ?: "--", style = MaterialTheme.typography.displayLarge, color = color)
        Column(Modifier.padding(start = 8.dp, top = 18.dp)) {
            Text("BPM", style = MaterialTheme.typography.titleMedium, color = Flex.TextMid)
            DataPill(s)
        }
    }
}

/** Estado del dato con la misma prioridad que el overlay (OverlayStatus.dataLine). */
@Composable
fun DataPill(s: MonitorSnapshot) {
    val line = com.flexhealth.heartmonitor.receiver.overlay.OverlayStatus.dataLine(s)
    if (line == null) { Pill("En vivo", Flex.Ok); return }
    Pill(line.text, if (line.severity == com.flexhealth.heartmonitor.receiver.overlay.Severity.BAD) Flex.Bad else Flex.Warn)
}

@Composable
fun FreshnessPill(f: Freshness) {
    val (text, c) = when (f) {
        Freshness.LIVE -> "En vivo" to Flex.Ok
        Freshness.STALE -> "Desactualizado" to Flex.Warn
        Freshness.LOST -> "Sin señal" to Flex.Bad
        Freshness.WAITING -> "Esperando" to Flex.TextLow
    }
    Pill(text, c)
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f)).border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Flex.TextLow)
        Text(value, style = MaterialTheme.typography.titleLarge, color = Flex.TextHigh)
    }
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = Flex.TextHigh) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = Flex.TextMid, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, fontWeight = FontWeight.Medium)
    }
}

/** Botón píldora de vidrio. */
@Composable
fun GlassButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Color? = null, enabled: Boolean = true) {
    val shape = RoundedCornerShape(50)
    val bg = if (accent != null) Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.75f)))
    else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.07f)))
    Box(
        modifier.clip(shape).background(bg).border(1.dp, Color.White.copy(alpha = if (accent != null) 0.25f else 0.18f), shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) Color.White else Flex.TextLow) }
}

@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Flex.TextHigh else Flex.TextLow)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Flex.TextLow) }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Flex.Heart, checkedThumbColor = Color.White, uncheckedTrackColor = Color.White.copy(alpha = 0.12f)))
    }
}

@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Row(modifier.clip(shape).background(Color.White.copy(alpha = 0.06f)).border(1.dp, Color.White.copy(alpha = 0.10f), shape).padding(3.dp)) {
        options.forEach { o ->
            val sel = o == selected
            Box(
                Modifier.weight(1f).clip(shape).background(if (sel) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onSelect(o) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label(o), style = MaterialTheme.typography.labelLarge, color = if (sel) Color.White else Flex.TextMid) }
        }
    }
}

@Composable
fun LabeledSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, onChange: (Float) -> Unit, onDone: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row { Text(title, style = MaterialTheme.typography.bodyLarge, color = Flex.TextHigh, modifier = Modifier.weight(1f)); Text(display, color = Flex.TextMid) }
        Slider(value = value, onValueChange = onChange, valueRange = range, onValueChangeFinished = onDone,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Flex.Heart, inactiveTrackColor = Color.White.copy(alpha = 0.14f)))
    }
}

fun LinkStatus.color(): Color = when (state) {
    LinkState.CONNECTED -> Flex.Ok
    LinkState.SEARCHING, LinkState.CONNECTING, LinkState.AUTHENTICATING, LinkState.PAIRING -> Flex.Warn
    LinkState.IDLE -> Flex.TextLow
    LinkState.UNAVAILABLE, LinkState.ERROR -> Flex.Bad
}

fun LinkStatus.label(): String = when (state) {
    LinkState.IDLE -> "Inactivo"
    LinkState.SEARCHING -> "Esperando reloj"
    LinkState.CONNECTING -> "Conectando"
    LinkState.PAIRING -> "Emparejando"
    LinkState.AUTHENTICATING -> "Autenticando"
    LinkState.CONNECTED -> "Conectado"
    LinkState.UNAVAILABLE -> "No disponible"
    LinkState.ERROR -> "Error"
}

fun SensorStatus.label(): String = when (this) {
    SensorStatus.ACTIVE -> "Sensor activo"
    SensorStatus.ACQUIRING -> "Buscando pulso"
    SensorStatus.OFF_BODY -> "Fuera de la muñeca"
    SensorStatus.UNAVAILABLE -> "Sensor no disponible"
    SensorStatus.PERMISSION_DENIED -> "Sin permiso en el reloj"
    SensorStatus.BUSY -> "Sensor en uso por otra app"
    SensorStatus.STOPPED -> "Detenido"
    SensorStatus.ERROR -> "Error del sensor"
    SensorStatus.UNKNOWN -> "—"
}

fun ReadingQuality.label(): String = when (this) {
    ReadingQuality.HIGH -> "Alta"; ReadingQuality.MEDIUM -> "Media"; ReadingQuality.LOW -> "Baja"
    ReadingQuality.UNRELIABLE -> "No fiable"; ReadingQuality.UNKNOWN -> "—"
}

@Composable
fun VSpace(h: Int) = Spacer(Modifier.height(h.dp))
