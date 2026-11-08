package com.flexhealth.heartmonitor.receiver.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flexhealth.heartmonitor.engine.Freshness
import com.flexhealth.heartmonitor.engine.HeartRateEngine
import com.flexhealth.heartmonitor.engine.MonitorSnapshot
import com.flexhealth.heartmonitor.protocol.LinkStatus
import com.flexhealth.heartmonitor.receiver.data.MonitorMode
import com.flexhealth.heartmonitor.receiver.data.ReceiverConfig
import com.flexhealth.heartmonitor.receiver.data.ReceiverSettings
import com.flexhealth.heartmonitor.receiver.ui.components.BpmHero
import com.flexhealth.heartmonitor.receiver.ui.components.GlassButton
import com.flexhealth.heartmonitor.receiver.ui.components.GlassCard
import com.flexhealth.heartmonitor.receiver.ui.components.HeartRateChart
import com.flexhealth.heartmonitor.receiver.ui.components.KeyValue
import com.flexhealth.heartmonitor.receiver.ui.components.Pill
import com.flexhealth.heartmonitor.receiver.ui.components.SectionTitle
import com.flexhealth.heartmonitor.receiver.ui.components.Segmented
import com.flexhealth.heartmonitor.receiver.ui.components.StatTile
import com.flexhealth.heartmonitor.receiver.ui.components.VSpace
import com.flexhealth.heartmonitor.receiver.ui.components.color
import com.flexhealth.heartmonitor.receiver.ui.components.label
import com.flexhealth.heartmonitor.receiver.ui.theme.Flex

@Composable
fun MonitorScreen(
    s: MonitorSnapshot, link: LinkStatus, config: ReceiverConfig, running: Boolean, engine: HeartRateEngine, wide: Boolean,
    onStart: () -> Unit, onStop: () -> Unit, onWindow: (Int) -> Unit,
) {
    val hero = @Composable {
        GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Frecuencia cardíaca", style = MaterialTheme.typography.titleMedium, color = Flex.TextMid, modifier = Modifier.weight(1f))
                Pill(link.label(), link.color())
            }
            VSpace(8)
            BpmHero(s)
            VSpace(14)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Mín 60 s", s.windowMin?.toString() ?: "--", Modifier.weight(1f))
                StatTile("Media", s.windowAvg?.toString() ?: "--", Modifier.weight(1f))
                StatTile("Máx 60 s", s.windowMax?.toString() ?: "--", Modifier.weight(1f))
            }
            VSpace(16)
            if (running) GlassButton("Detener monitorización", onStop, Modifier.fillMaxWidth())
            else GlassButton("Iniciar monitorización", onStart, Modifier.fillMaxWidth(), accent = Flex.Heart)
            VSpace(8)
            Text("Solo bienestar y fitness. No es un dispositivo médico ni sirve para diagnóstico.",
                style = MaterialTheme.typography.bodyMedium, color = Flex.TextLow)
        }
    }
    val chart = @Composable {
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Historial") {}
            Segmented(ReceiverSettings.GRAPH_WINDOWS, config.graphWindowSec, { "${it}s" }, onWindow, Modifier.fillMaxWidth())
            VSpace(12)
            HeartRateChart(engine, s.historyVersion, config.graphWindowSec, dimmed = s.freshness != Freshness.LIVE, modifier = Modifier.fillMaxWidth().height(if (wide) 260.dp else 190.dp))
        }
    }
    val watch = @Composable {
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Reloj") {}
            KeyValue("Modo", if (config.mode == MonitorMode.PHONE_BLUETOOTH) "Smartphone · Data Layer" else "Tablet · Wi-Fi directo")
            KeyValue("Dispositivo", link.peerName ?: s.device ?: "—")
            KeyValue("Modelo", s.device ?: "—")
            KeyValue("Sensor", s.sensorStatus.label())
            KeyValue("Calidad", s.quality.label())
            KeyValue("Fuente", s.source ?: "—")
            link.nearby?.let { KeyValue("Conexión directa", if (it) "Sí" else "No (ruta por red)", if (it) Flex.Ok else Flex.Warn) }
            link.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Flex.TextLow) }
        }
    }
    if (wide) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(0.9f), verticalArrangement = Arrangement.spacedBy(20.dp)) { hero(); watch() }
            Column(Modifier.weight(1.1f)) { chart() }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { hero(); chart(); watch() }
    }
    VSpace(8)
}
