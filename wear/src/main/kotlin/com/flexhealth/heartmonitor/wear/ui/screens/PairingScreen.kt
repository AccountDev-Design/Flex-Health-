package com.flexhealth.heartmonitor.wear.ui.screens

import android.app.RemoteInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.flexhealth.heartmonitor.core.android.ble.BlePermissions
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import com.flexhealth.heartmonitor.wear.R
import com.flexhealth.heartmonitor.wear.ui.PairingUi
import com.flexhealth.heartmonitor.wear.ui.PairingViewModel
import com.flexhealth.heartmonitor.wear.ui.theme.HeartRed

private const val KEY_IP = "ip"

@Composable
fun PairingScreen(onDone: () -> Unit, vm: PairingViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val deniedText = stringResource(R.string.ble_perm_denied)
    val blePermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) vm.startBleDiscovery() else vm.fail(deniedText)
    }
    val startBle = {
        val perms = BlePermissions.central()
        if (BlePermissions.granted(context, perms)) vm.startBleDiscovery() else blePermissions.launch(perms)
    }
    val manualHint = stringResource(R.string.manual_ip_hint)
    val manual = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(KEY_IP)?.toString() }
        if (!text.isNullOrBlank()) vm.pairManual(text)
    }
    val launchManual = {
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder(KEY_IP).setLabel(manualHint).build()))
        manual.launch(intent)
    }
    val listState = rememberScalingLazyListState()

    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            when (val s = ui) {
                PairingUi.Idle -> {
                    item { ListHeader { Text(stringResource(R.string.pair_method), textAlign = TextAlign.Center) } }
                    item { Button(onClick = { vm.startDiscovery() }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.method_wifi)) }) }
                    item { Button(onClick = startBle, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.method_ble)) }) }
                    item { Text(stringResource(R.string.ble_perm_rationale), fontSize = 11.sp, textAlign = TextAlign.Center) }
                }
                is PairingUi.BleSearching -> {
                    item { ListHeader { Text(stringResource(R.string.method_ble)) } }
                    if (s.found.isEmpty()) {
                        item { CircularProgressIndicator() }
                        item { Text(stringResource(R.string.searching_ble), fontSize = 12.sp, textAlign = TextAlign.Center) }
                    }
                    s.found.forEach { t ->
                        item {
                            Button(onClick = { vm.pairBle(t) }, modifier = Modifier.fillMaxWidth(), label = { Text(t.name) },
                                secondaryLabel = { Text("${t.rssi} dBm") })
                        }
                    }
                    item { FilledTonalButton(onClick = { vm.chooseMethod() }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.change_method)) }) }
                }
                is PairingUi.Searching -> {
                    item { ListHeader { Text(stringResource(R.string.pair_tablet)) } }
                    val found = (s as? PairingUi.Searching)?.found.orEmpty()
                    if (found.isEmpty()) {
                        item { CircularProgressIndicator() }
                        item { Text(stringResource(R.string.searching), fontSize = 12.sp, textAlign = TextAlign.Center) }
                    }
                    found.forEach { r ->
                        item {
                            Button(onClick = { vm.pair(r) }, modifier = Modifier.fillMaxWidth(), label = { Text(r.name) },
                                secondaryLabel = { Text(r.endpoint.host) })
                        }
                    }
                    item { FilledTonalButton(onClick = launchManual, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.manual_ip)) }) }
                    item { FilledTonalButton(onClick = { vm.chooseMethod() }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.change_method)) }) }
                }
                PairingUi.NoWifi -> {
                    item { Text(stringResource(R.string.no_wifi), textAlign = TextAlign.Center, fontSize = 13.sp) }
                    item { Button(onClick = { vm.startDiscovery() }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.retry)) }) }
                }
                is PairingUi.Connecting -> {
                    item { CircularProgressIndicator() }
                    item { Text("${stringResource(R.string.connecting)}\n${s.target}", textAlign = TextAlign.Center) }
                }
                is PairingUi.Code -> {
                    item { ListHeader { Text(stringResource(R.string.code_title)) } }
                    item {
                        Text(s.code.chunked(3).joinToString(" "), fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth())
                    }
                    item { Text("${stringResource(R.string.code_hint)}: ${s.receiverName}", fontSize = 12.sp, textAlign = TextAlign.Center) }
                    item { Button(onClick = { vm.answerCode(true) }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.code_yes)) }) }
                    item { FilledTonalButton(onClick = { vm.answerCode(false) }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.code_no)) }) }
                }
                PairingUi.WaitingTablet -> {
                    item { CircularProgressIndicator() }
                    item { Text(stringResource(R.string.waiting_tablet), textAlign = TextAlign.Center) }
                }
                is PairingUi.Success -> {
                    item { Text(stringResource(R.string.paired_ok, s.receiverName), textAlign = TextAlign.Center) }
                    item { Button(onClick = onDone, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.done)) }) }
                }
                is PairingUi.Failed -> {
                    item { Text(s.message, color = HeartRed, textAlign = TextAlign.Center, fontSize = 12.sp) }
                    item { Button(onClick = { vm.chooseMethod() }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.retry)) }) }
                }
            }
        }
    }
}
