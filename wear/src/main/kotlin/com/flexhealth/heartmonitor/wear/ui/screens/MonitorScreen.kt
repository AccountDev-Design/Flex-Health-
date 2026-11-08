package com.flexhealth.heartmonitor.wear.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import com.flexhealth.heartmonitor.protocol.LinkStatus
import com.flexhealth.heartmonitor.protocol.SensorStatus
import com.flexhealth.heartmonitor.wear.R
import com.flexhealth.heartmonitor.wear.service.MonitoringRepository
import com.flexhealth.heartmonitor.wear.service.MonitoringService
import com.flexhealth.heartmonitor.wear.ui.theme.HeartRed
import kotlinx.coroutines.delay

@Composable
fun MonitorScreen(onSettings: () -> Unit) {
    val context = LocalContext.current
    val state by MonitoringRepository.state.collectAsStateWithLifecycle()
    // Reloj de UI a 1 Hz solo mientras la pantalla está visible (para ocultar BPM antiguos).
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.running) { while (state.running) { now = SystemClock.elapsedRealtime(); delay(1_000) } }
    val fresh = state.lastReadingElapsed?.let { now - it < 5_000 } == true
    val listState = rememberScalingLazyListState()

    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    Image(painterResource(R.drawable.ic_heart), contentDescription = null, modifier = Modifier.size(30.dp))
                    Text(
                        text = if (state.running && fresh) state.lastBpm?.toString() ?: "--" else "--",
                        fontSize = 46.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp),
                    )
                    Text(stringResource(R.string.bpm), fontSize = 13.sp, color = Color.LightGray, modifier = Modifier.padding(start = 4.dp, top = 14.dp))
                }
            }
            item {
                Text(
                    stringResource(state.sensorStatus.label()), textAlign = TextAlign.Center, fontSize = 13.sp,
                    color = if (state.sensorStatus == SensorStatus.ACTIVE) Color.White else HeartRed,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.running) {
                item {
                    Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        state.phone?.let { LinkDot(stringResource(R.string.link_phone), it) }
                        state.tablet?.let { LinkDot(stringResource(R.string.link_tablet), it) }
                    }
                }
            }
            item {
                if (state.running) {
                    Button(onClick = { MonitoringService.stop(context) }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.stop)) })
                } else {
                    Button(onClick = { MonitoringService.start(context) }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.start)) })
                }
            }
            if (!state.running && state.sensorStatus == SensorStatus.BUSY) {
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

@Composable
private fun LinkDot(label: String, status: LinkStatus) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
        Box(Modifier.size(8.dp).background(status.color(), CircleShape))
        Text(label, fontSize = 11.sp, modifier = Modifier.padding(start = 3.dp))
    }
}
