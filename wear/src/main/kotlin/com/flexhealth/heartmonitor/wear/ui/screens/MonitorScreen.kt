package com.flexhealth.heartmonitor.wear.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.flexhealth.heartmonitor.protocol.LinkState
import com.flexhealth.heartmonitor.protocol.LinkStatus
import com.flexhealth.heartmonitor.protocol.ReadingQuality
import com.flexhealth.heartmonitor.protocol.SensorStatus
import com.flexhealth.heartmonitor.wear.R
import com.flexhealth.heartmonitor.wear.service.MonitoringRepository
import com.flexhealth.heartmonitor.wear.service.MonitoringService
import com.flexhealth.heartmonitor.wear.service.WatchMonitorState
import com.flexhealth.heartmonitor.wear.ui.theme.HeartRed
import com.flexhealth.heartmonitor.wear.ui.theme.StatusWarn
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Solo lo que la pantalla muestra: los cambios de RTT, detalles o contadores no la recomponen. */
@Immutable
internal data class MonitorUi(
    val running: Boolean = false,
    val bpm: Int? = null,
    val reliable: Boolean = true,
    val status: SensorStatus = SensorStatus.STOPPED,
    val lastReadingElapsed: Long? = null,
    val phone: LinkState? = null,
    val tablet: LinkState? = null,
)

internal fun WatchMonitorState.toUi() = MonitorUi(
    running, lastBpm, lastQuality != ReadingQuality.UNRELIABLE, sensorStatus, lastReadingElapsed, phone?.state, tablet?.state,
)

private const val STALE_AFTER_MS = 5_000L

@Composable
fun MonitorScreen(onSettings: () -> Unit) {
    val context = LocalContext.current
    val uiFlow = remember { monitorUiFlow() }
    val ui by uiFlow.collectAsStateWithLifecycle(remember { initialMonitorUi() })
    val listState = rememberScalingLazyListState()

    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { BpmRow(ui) }
            item {
                Text(
                    stringResource(ui.status.label()), textAlign = TextAlign.Center, fontSize = 13.sp,
                    color = if (ui.status == SensorStatus.ACTIVE) Color.White else HeartRed,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (ui.running) {
                item {
                    Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        ui.phone?.let { LinkDot(stringResource(R.string.link_phone), it) }
                        ui.tablet?.let { LinkDot(stringResource(R.string.link_tablet), it) }
                    }
                }
            }
            item {
                if (ui.running) {
                    Button(onClick = { MonitoringService.stop(context) }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.stop)) })
                } else {
                    Button(onClick = { MonitoringService.start(context) }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.start)) })
                }
            }
            if (!ui.running && ui.status == SensorStatus.BUSY) {
                item {
                    FilledTonalButton(
                        onClick = { MonitoringService.start(context, allowTakeover = true) }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.start_takeover)) },
                        secondaryLabel = { Text(stringResource(R.string.takeover_hint)) },
                    )
                }
            }
            item {
                FilledTonalButton(onClick = onSettings, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.settings)) })
            }
            item {
                Text(stringResource(R.string.wellness_note), fontSize = 10.sp, color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * BPM grande. Sin bucle periódico: un único temporizador oculta el valor cuando cumple 5 s sin lecturas
 * nuevas. No se muestra si el sensor no está activo (fuera de la muñeca, buscando pulso…).
 */
@Composable
private fun BpmRow(ui: MonitorUi) {
    var fresh by remember(ui.lastReadingElapsed) {
        mutableStateOf(ui.lastReadingElapsed?.let { SystemClock.elapsedRealtime() - it < STALE_AFTER_MS } == true)
    }
    LaunchedEffect(ui.lastReadingElapsed) {
        val at = ui.lastReadingElapsed ?: return@LaunchedEffect
        val left = STALE_AFTER_MS - (SystemClock.elapsedRealtime() - at)
        if (left > 0) delay(left)
        fresh = false
    }
    val shown = ui.bpm.takeIf { ui.running && fresh && ui.status == SensorStatus.ACTIVE }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            Image(painterResource(R.drawable.ic_heart), contentDescription = null, modifier = Modifier.size(30.dp))
            Text(
                text = shown?.toString() ?: "--", fontSize = 46.sp, fontWeight = FontWeight.Bold,
                color = if (shown != null && !ui.reliable) StatusWarn else Color.White,
                modifier = Modifier.padding(start = 6.dp),
            )
            Text(stringResource(R.string.bpm), fontSize = 13.sp, color = Color.LightGray, modifier = Modifier.padding(start = 4.dp, top = 14.dp))
        }
        if (shown != null && !ui.reliable) Text(stringResource(R.string.unreliable), fontSize = 11.sp, color = StatusWarn)
    }
}

@Composable
private fun LinkDot(label: String, state: LinkState) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
        Box(Modifier.size(8.dp).background(LinkStatus(state).color(), CircleShape))
        Text(label, fontSize = 11.sp, modifier = Modifier.padding(start = 3.dp))
    }
}

/** Flujo de la UI del reloj: solo emite cuando cambia algo visible. */
internal fun monitorUiFlow() = MonitoringRepository.state.map { it.toUi() }.distinctUntilChanged()

/** Valor inicial (fuera de la composición) para evitar un fotograma con datos vacíos. */
internal fun initialMonitorUi() = MonitoringRepository.state.value.toUi()
