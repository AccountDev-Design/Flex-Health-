package com.flexhealth.heartmonitor.wear.ui.screens

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.flexhealth.heartmonitor.protocol.LinkState
import com.flexhealth.heartmonitor.protocol.LinkStatus
import com.flexhealth.heartmonitor.protocol.SensorStatus
import com.flexhealth.heartmonitor.wear.R
import com.flexhealth.heartmonitor.wear.ui.theme.HeartRed
import com.flexhealth.heartmonitor.wear.ui.theme.StatusOk
import com.flexhealth.heartmonitor.wear.ui.theme.StatusWarn

@StringRes
internal fun SensorStatus.label(): Int = when (this) {
    SensorStatus.ACTIVE -> R.string.status_active
    SensorStatus.ACQUIRING -> R.string.status_acquiring
    SensorStatus.OFF_BODY -> R.string.status_off_body
    SensorStatus.UNAVAILABLE -> R.string.status_unavailable
    SensorStatus.PERMISSION_DENIED -> R.string.status_permission
    SensorStatus.BUSY -> R.string.status_busy
    SensorStatus.STOPPED -> R.string.status_stopped
    SensorStatus.ERROR -> R.string.status_error
    SensorStatus.UNKNOWN -> R.string.status_unknown
}

internal fun LinkStatus?.color(): Color = when (this?.state) {
    LinkState.CONNECTED -> StatusOk
    LinkState.SEARCHING, LinkState.CONNECTING, LinkState.AUTHENTICATING, LinkState.PAIRING -> StatusWarn
    null, LinkState.IDLE -> Color.Gray
    else -> HeartRed
}
