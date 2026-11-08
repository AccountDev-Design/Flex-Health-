package com.flexhealth.heartmonitor.wear.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.flexhealth.heartmonitor.wear.R
import com.flexhealth.heartmonitor.wear.ui.theme.HeartRed

/** Explica el motivo del permiso antes de solicitarlo. */
@Composable
fun PermissionScreen(denied: Boolean, onGrant: () -> Unit) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding) {
            item { ListHeader { Text(stringResource(R.string.perm_title), textAlign = TextAlign.Center) } }
            item { Text(stringResource(R.string.perm_body), fontSize = 12.sp, textAlign = TextAlign.Center) }
            if (denied) item { Text(stringResource(R.string.perm_denied), color = HeartRed, fontSize = 12.sp, textAlign = TextAlign.Center) }
            item { Button(onClick = onGrant, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.perm_grant)) }) }
        }
    }
}
