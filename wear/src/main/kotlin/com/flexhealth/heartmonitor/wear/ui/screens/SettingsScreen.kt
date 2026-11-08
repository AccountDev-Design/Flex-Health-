package com.flexhealth.heartmonitor.wear.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.flexhealth.heartmonitor.wear.R
import com.flexhealth.heartmonitor.wear.sensor.HeartRateSourceIds
import com.flexhealth.heartmonitor.wear.service.MonitoringRepository
import com.flexhealth.heartmonitor.wear.settings.WatchConfig
import com.flexhealth.heartmonitor.wear.wearApp
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onPair: () -> Unit) {
    val app = LocalContext.current.wearApp
    val cfg by app.settings.config.collectAsStateWithLifecycle(WatchConfig())
    val running by MonitoringRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()
    val editable = !running.running // los cambios se aplican al iniciar la siguiente sesión

    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text(stringResource(R.string.destinations)) } }
            item {
                SwitchButton(checked = cfg.sendToPhone, enabled = editable, onCheckedChange = { v -> scope.launch { app.settings.setSendToPhone(v) } },
                    modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.dest_phone)) })
            }
            item {
                SwitchButton(checked = cfg.sendToTablet && cfg.tabletPairingId != null, enabled = editable && cfg.tabletPairingId != null,
                    onCheckedChange = { v -> scope.launch { app.settings.setSendToTablet(v) } }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.dest_tablet)) },
                    secondaryLabel = { Text(cfg.tabletName?.let { stringResource(R.string.paired_with, it) } ?: stringResource(R.string.not_paired)) })
            }
            item {
                FilledTonalButton(onClick = onPair, enabled = editable, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.pair_tablet)) })
            }
            if (cfg.tabletPairingId != null) {
                item {
                    FilledTonalButton(onClick = {
                        scope.launch { cfg.tabletPairingId?.let { app.pairings.delete(it) }; app.settings.clearTablet() }
                    }, enabled = editable, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.unpair)) })
                }
            }
            item { ListHeader { Text(stringResource(R.string.source)) } }
            item {
                RadioButton(selected = cfg.sourceId == HeartRateSourceIds.EXERCISE, enabled = editable,
                    onSelect = { scope.launch { app.settings.setSource(HeartRateSourceIds.EXERCISE) } }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.source_exercise)) }, secondaryLabel = { Text(stringResource(R.string.source_exercise_hint)) })
            }
            item {
                RadioButton(selected = cfg.sourceId == HeartRateSourceIds.MEASURE, enabled = editable,
                    onSelect = { scope.launch { app.settings.setSource(HeartRateSourceIds.MEASURE) } }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.source_measure)) }, secondaryLabel = { Text(stringResource(R.string.source_measure_hint)) })
            }
            item {
                SwitchButton(checked = cfg.lowLatency, enabled = editable, onCheckedChange = { v -> scope.launch { app.settings.setLowLatency(v) } },
                    modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.low_latency)) },
                    secondaryLabel = { Text(stringResource(R.string.low_latency_hint)) })
            }
        }
    }
}
