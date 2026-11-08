package com.flexhealth.heartmonitor.receiver.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flexhealth.heartmonitor.receiver.ui.components.GlassButton
import com.flexhealth.heartmonitor.receiver.ui.components.GlassCard
import com.flexhealth.heartmonitor.receiver.ui.components.Pill
import com.flexhealth.heartmonitor.receiver.ui.components.SectionTitle
import com.flexhealth.heartmonitor.receiver.ui.components.VSpace
import com.flexhealth.heartmonitor.receiver.ui.theme.Flex

data class PermissionItem(val title: String, val why: String, val granted: Boolean, val optional: Boolean, val onRequest: () -> Unit)

@Composable
fun PermissionsScreen(items: List<PermissionItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items.forEach { p ->
            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle(p.title) {
                    Pill(if (p.granted) "Concedido" else if (p.optional) "Opcional" else "Necesario", if (p.granted) Flex.Ok else if (p.optional) Flex.TextMid else Flex.Warn)
                }
                Text(p.why, style = MaterialTheme.typography.bodyMedium, color = Flex.TextMid)
                if (!p.granted) {
                    VSpace(10)
                    Row(verticalAlignment = Alignment.CenterVertically) { GlassButton("Conceder", p.onRequest, Modifier.fillMaxWidth(), accent = Flex.Heart) }
                }
            }
        }
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Privacidad") {}
            Text(
                "La frecuencia cardíaca solo viaja entre tu reloj y tus dispositivos emparejados. No se envía a Internet ni se guarda: " +
                    "el historial vive en memoria y se borra al detener la monitorización. En modo smartphone, Wear Data Layer puede " +
                    "enrutar por servidores de Google (cifrado extremo a extremo) si el Bluetooth no está disponible.",
                style = MaterialTheme.typography.bodyMedium, color = Flex.TextMid,
            )
        }
    }
}
