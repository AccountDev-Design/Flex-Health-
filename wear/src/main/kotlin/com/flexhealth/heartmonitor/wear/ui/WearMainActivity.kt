package com.flexhealth.heartmonitor.wear.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.flexhealth.heartmonitor.wear.ui.screens.MonitorScreen
import com.flexhealth.heartmonitor.wear.ui.screens.PairingScreen
import com.flexhealth.heartmonitor.wear.ui.screens.PermissionScreen
import com.flexhealth.heartmonitor.wear.ui.screens.SettingsScreen
import com.flexhealth.heartmonitor.wear.ui.theme.FlexWearTheme

class WearMainActivity : ComponentActivity() {
    private var permissionsGranted by mutableStateOf(false)
    private var permissionDenied by mutableStateOf(false)

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsGranted = hasHeartRatePermission()
        permissionDenied = !permissionsGranted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionsGranted = hasHeartRatePermission()
        setContent {
            FlexWearTheme {
                AppScaffold {
                    if (!permissionsGranted) {
                        PermissionScreen(denied = permissionDenied, onGrant = { requestPermissions.launch(requiredPermissions()) })
                    } else {
                        val nav = rememberSwipeDismissableNavController()
                        SwipeDismissableNavHost(navController = nav, startDestination = "monitor") {
                            composable("monitor") { MonitorScreen(onSettings = { nav.navigate("settings") }) }
                            composable("settings") { SettingsScreen(onPair = { nav.navigate("pair") }) }
                            composable("pair") { PairingScreen(onDone = { nav.popBackStack() }) }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionsGranted = hasHeartRatePermission()
    }

    private fun heartRatePermission(): String =
        if (Build.VERSION.SDK_INT >= 36) HEALTH_READ_HEART_RATE else Manifest.permission.BODY_SENSORS

    private fun hasHeartRatePermission() =
        ContextCompat.checkSelfPermission(this, heartRatePermission()) == PackageManager.PERMISSION_GRANTED

    /** Se solicita solo lo necesario: FC y, desde API 33, notificaciones del servicio. */
    private fun requiredPermissions(): Array<String> = buildList {
        add(heartRatePermission())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private companion object {
        /** HealthPermissions.READ_HEART_RATE (API 36, Wear OS 6). */
        const val HEALTH_READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
    }
}
