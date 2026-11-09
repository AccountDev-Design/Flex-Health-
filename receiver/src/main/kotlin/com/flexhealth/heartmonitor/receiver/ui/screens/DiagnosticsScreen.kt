package com.flexhealth.heartmonitor.receiver.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flexhealth.heartmonitor.engine.MonitorSnapshot
import com.flexhealth.heartmonitor.protocol.LinkStatus
import com.flexhealth.heartmonitor.protocol.ProtocolConstants
import com.flexhealth.heartmonitor.receiver.BuildConfig
import com.flexhealth.heartmonitor.receiver.data.MonitorMode
import com.flexhealth.heartmonitor.receiver.ui.components.GlassCard
import com.flexhealth.heartmonitor.receiver.ui.components.KeyValue
import com.flexhealth.heartmonitor.receiver.ui.components.SectionTitle
import com.flexhealth.heartmonitor.receiver.ui.components.color
import com.flexhealth.heartmonitor.receiver.ui.components.label
import com.flexhealth.heartmonitor.receiver.ui.theme.Flex

/** Diagnóstico de conexión: todo lo mostrado sale de contadores reales del motor y del transporte. */
@Composable
fun DiagnosticsScreen(s: MonitorSnapshot, link: LinkStatus, mode: MonitorMode) {
    val st = s.stats
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Enlace") {}
            KeyValue("Transporte", if (mode == MonitorMode.PHONE_BLUETOOTH) "Wear Data Layer" else "WebSocket local cifrado")
            KeyValue("Estado", link.label(), link.color())
            KeyValue("Par", link.peerName ?: "—")
            KeyValue("Detalle", link.detail ?: "—")
            link.nearby?.let { KeyValue("Node.isNearby", it.toString()) }
            KeyValue("RTT", link.rttMs?.let { "$it ms" } ?: "—")
        }
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Datos") {}
            KeyValue("Sesión", s.sessionId ?: "—")
            KeyValue("Actualidad", s.freshness.name)
            KeyValue("Antigüedad de la última muestra", s.sampleAgeMs?.let { "$it ms" } ?: "—")
            KeyValue("Retraso de la última entrega", s.lastLatenessMs?.let { "$it ms" } ?: "—")
            KeyValue("Desfase de relojes estimado", s.clockOffsetMs?.let { "$it ms" } ?: "—")
            KeyValue("Paquetes recibidos", st.received.toString())
            KeyValue("Muestras válidas", st.samples.toString())
            KeyValue("Paquetes de estado", st.statusPackets.toString())
            KeyValue("Inválidos", st.invalid.toString(), if (st.invalid > 0) Flex.Warn else Flex.TextHigh)
            KeyValue("Duplicados", st.duplicates.toString())
            KeyValue("Fuera de orden", st.outOfOrder.toString())
            KeyValue("Atrasados (≥ 2,5 s)", st.late.toString())
            KeyValue("Perdidos (huecos de secuencia)", st.lost.toString(), if (st.lost > 0) Flex.Warn else Flex.TextHigh)
            KeyValue("Demasiado antiguos", st.tooOld.toString())
            KeyValue("No fiables (según el sensor)", st.unreliable.toString(), if (st.unreliable > 0) Flex.Warn else Flex.TextHigh)
            KeyValue("Picos descartados", st.suspicious.toString(), if (st.suspicious > 0) Flex.Warn else Flex.TextHigh)
            KeyValue("Saltos confirmados", st.confirmedJumps.toString())
            KeyValue("Sesiones", st.sessions.toString())
        }
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Dispositivos") {}
            KeyValue("Reloj (modelo informado)", s.device ?: "—")
            KeyValue("Este dispositivo", "${Build.MANUFACTURER} ${Build.MODEL}")
            KeyValue("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            KeyValue("App", "${BuildConfig.VERSION_NAME} · ${BuildConfig.FLAVOR}")
            KeyValue("Protocolo", "v${ProtocolConstants.PROTOCOL_VERSION}")
        }
    }
}
