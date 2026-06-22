package com.edge2.remote

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.edge2.remote.ble.ConnectionState
import com.edge2.remote.ui.ConnectionScreen
import com.edge2.remote.ui.ControllerScreen
import com.edge2.remote.ui.RemoteScreen
import com.edge2.remote.ui.theme.Edge2
import com.edge2.remote.ui.theme.Edge2Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Deep link contrôleur : edge2remote://control?ws=<url ws du host distant>
        val data = intent?.data
        val controllerWsUrl =
            if (data?.scheme == "edge2remote") data.getQueryParameter("ws") else null

        setContent {
            Edge2Theme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Edge2.colors.bg)
                        .safeDrawingPadding(),
                ) {
                    if (controllerWsUrl != null) {
                        ControllerScreen(wsUrl = controllerWsUrl)
                    } else {
                        App()
                    }
                }
            }
        }
    }
}

@Composable
private fun App() {
    val vm: RemoteViewModel = viewModel()
    val state by vm.connectionState.collectAsStateWithLifecycle()

    val discovered by vm.discovered.collectAsStateWithLifecycle()

    // Demande les permissions BLE (+ notifications) puis lance le scan. Le refus
    // de POST_NOTIFICATIONS ne bloque PAS le scan (la notif est optionnelle).
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val bleOk = result.filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }.all { it.value }
        if (bleOk) vm.scan()
    }

    fun requestScan() {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        permissionLauncher.launch(perms)
    }

    if (state is ConnectionState.Connected) {
        RemoteScreen(vm = vm, onDisconnect = { vm.disconnect() })
    } else {
        ConnectionScreen(
            state = state,
            discovered = discovered,
            onScan = ::requestScan,
            onSelect = { vm.connectTo(it) },
        )
    }
}
