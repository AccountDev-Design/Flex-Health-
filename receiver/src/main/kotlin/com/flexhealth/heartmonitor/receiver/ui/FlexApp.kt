package com.flexhealth.heartmonitor.receiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flexhealth.heartmonitor.engine.MonitorSnapshot
import com.flexhealth.heartmonitor.protocol.LinkStatus
import com.flexhealth.heartmonitor.protocol.crypto.PairingRecord
import com.flexhealth.heartmonitor.receiver.data.ReceiverConfig
import com.flexhealth.heartmonitor.receiver.transport.PairingUiState
import com.flexhealth.heartmonitor.receiver.ui.glass.GlassSurface
import com.flexhealth.heartmonitor.receiver.ui.glass.LiquidBackground
import com.flexhealth.heartmonitor.receiver.ui.screens.ConnectionScreen
import com.flexhealth.heartmonitor.receiver.ui.screens.DiagnosticsScreen
import com.flexhealth.heartmonitor.receiver.ui.screens.MonitorScreen
import com.flexhealth.heartmonitor.receiver.ui.screens.OverlayScreen
import com.flexhealth.heartmonitor.receiver.ui.screens.PermissionItem
import com.flexhealth.heartmonitor.receiver.ui.screens.PermissionsScreen
import com.flexhealth.heartmonitor.receiver.ui.theme.Flex

enum class Tab(val label: String, val icon: ImageVector) {
    MONITOR("Monitor", Icons.Filled.Favorite),
    CONNECTION("Conexión", Icons.Filled.Share),
    OVERLAY("Overlay", Icons.Filled.Edit),
    DIAGNOSTICS("Diagnóstico", Icons.Filled.Build),
    PERMISSIONS("Permisos", Icons.Filled.Lock),
}

/** Estructura adaptable: barra inferior en móvil, raíl lateral en tablet (≥ 600 dp de ancho). */
@Composable
fun FlexApp(
    vm: MainViewModel, config: ReceiverConfig, snapshot: MonitorSnapshot, link: LinkStatus, running: Boolean,
    pairing: PairingUiState, paired: List<PairingRecord>, canDrawOverlays: Boolean, onGrantOverlay: () -> Unit,
    permissions: List<PermissionItem>,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.MONITOR) }
    LiquidBackground {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val wide = maxWidth >= 600.dp
            val content = @Composable { mod: Modifier ->
                Column(mod.verticalScroll(rememberScrollState()).padding(if (wide) PaddingValues(28.dp) else PaddingValues(16.dp, 12.dp, 16.dp, 110.dp))) {
                    Header(tab, wide)
                    when (tab) {
                        Tab.MONITOR -> MonitorScreen(snapshot, link, config, running, vm.engine, wide, vm::start, vm::stop, { vm.setWindow(it) })
                        Tab.CONNECTION -> ConnectionScreen(
                            config.mode, link, running, pairing, paired, vm.localAddress(), vm.serverPort(),
                            { vm.setMode(it) }, { vm.openPairing() }, vm::closePairing, vm::accept, vm::reject, vm::forget,
                        )
                        Tab.OVERLAY -> OverlayScreen(config.overlay, canDrawOverlays, running, { f -> vm.updateOverlay(f) }, onGrantOverlay)
                        Tab.DIAGNOSTICS -> DiagnosticsScreen(snapshot, link, config.mode)
                        Tab.PERMISSIONS -> PermissionsScreen(permissions)
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    GlassSurface(Modifier.padding(16.dp).fillMaxHeight().width(108.dp), shape = RoundedCornerShape(32.dp)) {
                        Column(Modifier.fillMaxSize().padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Tab.entries.forEach { t -> NavItem(t, t == tab) { tab = t } }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) { content(Modifier.fillMaxSize().widthIn(max = 1200.dp)) }
                }
            } else {
                content(Modifier.fillMaxSize())
                GlassSurface(Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth(), shape = RoundedCornerShape(30.dp), scrim = Flex.Ink.copy(alpha = 0.92f)) {
                    Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Tab.entries.forEach { t -> NavItem(t, t == tab, Modifier.weight(1f), compact = true) { tab = t } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(tab: Tab, wide: Boolean) {
    Column(Modifier.padding(bottom = 16.dp, top = if (wide) 0.dp else 8.dp)) {
        Text("FLEX HEART MONITOR", style = MaterialTheme.typography.labelMedium, color = Flex.HeartSoft)
        Text(tab.label, style = MaterialTheme.typography.headlineMedium, color = Flex.TextHigh)
    }
}

@Composable
private fun NavItem(t: Tab, selected: Boolean, modifier: Modifier = Modifier, compact: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.clip(shape).background(if (selected) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .then(if (selected) Modifier.border(1.dp, Color.White.copy(alpha = 0.18f), shape) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = if (compact) 2.dp else 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(t.icon, contentDescription = t.label, tint = if (selected) Flex.Heart else Flex.TextMid, modifier = Modifier.size(22.dp))
        Text(t.label, style = if (compact) MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp, letterSpacing = 0.sp) else MaterialTheme.typography.labelMedium,
            color = if (selected) Color.White else Flex.TextLow, maxLines = 1, softWrap = false)
    }
}


