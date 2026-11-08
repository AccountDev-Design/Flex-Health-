package com.flexhealth.heartmonitor.receiver.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flexhealth.heartmonitor.protocol.LinkStatus
import com.flexhealth.heartmonitor.protocol.ProtocolConstants
import com.flexhealth.heartmonitor.protocol.crypto.PairingRecord
import com.flexhealth.heartmonitor.lan.PairingRequest
import com.flexhealth.heartmonitor.receiver.data.MonitorMode
import com.flexhealth.heartmonitor.receiver.transport.PairingUiState
import com.flexhealth.heartmonitor.receiver.ui.components.GlassButton
import com.flexhealth.heartmonitor.receiver.ui.components.GlassCard
import com.flexhealth.heartmonitor.receiver.ui.components.KeyValue
import com.flexhealth.heartmonitor.receiver.ui.components.Pill
import com.flexhealth.heartmonitor.receiver.ui.components.SectionTitle
import com.flexhealth.heartmonitor.receiver.ui.components.VSpace
import com.flexhealth.heartmonitor.receiver.ui.components.color
import com.flexhealth.heartmonitor.receiver.ui.components.label
import com.flexhealth.heartmonitor.receiver.ui.glass.GlassSurface
import com.flexhealth.heartmonitor.receiver.ui.theme.Flex
import java.text.DateFormat
import java.util.Date

@Composable
fun ConnectionScreen(
    mode: MonitorMode, link: LinkStatus, running: Boolean,
    pairing: PairingUiState, paired: List<PairingRecord>, address: String?, port: Int,
    onMode: (MonitorMode) -> Unit, onOpenPairing: () -> Unit, onClosePairing: () -> Unit,
    onAccept: (PairingRequest) -> Unit, onReject: (PairingRequest) -> Unit, onForget: (PairingRecord) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Modo de conexión") { Pill(link.label(), link.color()) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModeTile("Smartphone", "Reloj → Data Layer → este móvil", mode == MonitorMode.PHONE_BLUETOOTH, Modifier.weight(1f)) { onMode(MonitorMode.PHONE_BLUETOOTH) }
                ModeTile("Tablet", "Reloj → Wi-Fi local → esta tablet", mode == MonitorMode.TABLET_WIFI, Modifier.weight(1f)) { onMode(MonitorMode.TABLET_WIFI) }
            }
            VSpace(10)
            Text(
                if (mode == MonitorMode.PHONE_BLUETOOTH)
                    "Usa el teléfono emparejado con el reloj en la app Galaxy Wearable. Data Layer usa Bluetooth cuando el reloj está cerca; si no, puede enrutar por red (se indica en «Conexión directa»)."
                else
                    "El reloj se conecta directamente a esta tablet por la red Wi-Fi local, sin teléfono ni Internet. Ambos deben estar en la misma red.",
                style = MaterialTheme.typography.bodyMedium, color = Flex.TextLow,
            )
        }

        if (mode == MonitorMode.TABLET_WIFI) {
            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("Servidor local") {}
                KeyValue("Estado", if (running) link.label() else "Detenido", if (running) link.color() else Flex.TextLow)
                KeyValue("Dirección", if (running && port != 0) "${address ?: "sin Wi-Fi"}:$port" else "—")
                KeyValue("Descubrimiento", "mDNS ${ProtocolConstants.NSD_SERVICE_TYPE}")
                KeyValue("Cifrado", "AES-256-GCM tras emparejamiento ECDH P-256")
                link.rttMs?.let { KeyValue("Latencia (RTT)", "$it ms") }
            }
            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("Emparejamiento") {}
                val req = pairing.request
                when {
                    req != null -> {
                        Text("${req.deviceName} quiere emparejarse", style = MaterialTheme.typography.bodyLarge, color = Flex.TextHigh)
                        VSpace(8)
                        Text(req.code.chunked(3).joinToString(" "), fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Comprueba que el reloj muestra el mismo código y confírmalo también allí.", style = MaterialTheme.typography.bodyMedium, color = Flex.TextLow)
                        VSpace(12)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            GlassButton("Coincide · aceptar", { onAccept(req) }, Modifier.weight(1f), accent = Flex.Heart)
                            GlassButton("Rechazar", { onReject(req) }, Modifier.weight(1f))
                        }
                    }
                    pairing.windowOpen -> {
                        Text("Ventana abierta ${pairing.secondsLeft} s", style = MaterialTheme.typography.titleMedium, color = Flex.Warn)
                        Text("En el reloj: Flex Heart › Ajustes › Emparejar tablet. Si no aparece, usa «Dirección manual» con ${address ?: "la IP de la tablet"}.",
                            style = MaterialTheme.typography.bodyMedium, color = Flex.TextLow)
                        VSpace(10)
                        GlassButton("Cerrar ventana", onClosePairing, Modifier.fillMaxWidth())
                    }
                    else -> {
                        pairing.lastResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Flex.TextMid); VSpace(8) }
                        Text("Solo se aceptan nuevos relojes mientras la ventana de emparejamiento está abierta.", style = MaterialTheme.typography.bodyMedium, color = Flex.TextLow)
                        VSpace(10)
                        GlassButton("Emparejar un reloj", onOpenPairing, Modifier.fillMaxWidth(), accent = Flex.Heart)
                    }
                }
            }
            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("Relojes emparejados") {}
                if (paired.isEmpty()) Text("Ninguno", style = MaterialTheme.typography.bodyMedium, color = Flex.TextLow)
                paired.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.peerName, style = MaterialTheme.typography.bodyLarge, color = Flex.TextHigh)
                            Text("Desde ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.createdAtMs))}",
                                style = MaterialTheme.typography.bodyMedium, color = Flex.TextLow)
                        }
                        GlassButton("Olvidar", { onForget(r) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeTile(title: String, subtitle: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    GlassSurface(
        modifier.clickable(onClick = onClick).border(if (selected) 2.dp else 0.dp, if (selected) Flex.Heart else Color.Transparent, shape),
        shape = shape, tint = if (selected) 0.14f else 0.05f, elevation = 0.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (selected) Color.White else Flex.TextMid)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Flex.TextLow)
        }
    }
}
