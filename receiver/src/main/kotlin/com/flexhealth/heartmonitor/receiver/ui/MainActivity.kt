package com.flexhealth.heartmonitor.receiver.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flexhealth.heartmonitor.receiver.ui.screens.PermissionItem
import com.flexhealth.heartmonitor.receiver.ui.theme.FlexTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var canDrawOverlays by mutableStateOf(false)
    private var notificationsGranted by mutableStateOf(true)

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        refreshPermissions()
        setContent {
            FlexTheme {
                val config by vm.config.collectAsStateWithLifecycle()
                val snapshot by vm.snapshot.collectAsStateWithLifecycle()
                val link by vm.link.collectAsStateWithLifecycle()
                val running by vm.running.collectAsStateWithLifecycle()
                val pairing by vm.pairing.collectAsStateWithLifecycle()
                val paired by vm.paired.collectAsStateWithLifecycle()
                config?.let { cfg ->
                    FlexApp(
                        vm = vm, config = cfg, snapshot = snapshot, link = link, running = running, pairing = pairing, paired = paired,
                        canDrawOverlays = canDrawOverlays, onGrantOverlay = ::openOverlaySettings, permissions = permissionItems(),
                    )
                }
            }
        }
    }

    override fun onResume() { super.onResume(); refreshPermissions() }

    private fun refreshPermissions() {
        canDrawOverlays = Settings.canDrawOverlays(this)
        notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
    }

    /** Cada permiso se pide solo desde aquí, con su explicación, y nunca al abrir la app. */
    private fun permissionItems(): List<PermissionItem> = buildList {
        add(PermissionItem(
            "Mostrar sobre otras apps",
            "Permite el widget flotante con tus BPM encima de juegos como Geometry Dash. Sin él, la app funciona pero sin overlay.",
            canDrawOverlays, optional = false, onRequest = ::openOverlaySettings,
        ))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(PermissionItem(
                "Notificaciones",
                "Muestra la notificación del servicio con accesos rápidos (detener, mostrar overlay, desbloquear toques). El servicio funciona igualmente sin ella.",
                notificationsGranted, optional = true, onRequest = { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
            ))
        }
    }
}
