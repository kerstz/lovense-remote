package com.edge2.remote

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.edge2.remote.prefs.Settings
import com.edge2.remote.remote.RemoteController
import com.edge2.remote.ui.ConnectionScreen
import com.edge2.remote.ui.ControllerScreen
import com.edge2.remote.ui.RemoteScreen
import com.edge2.remote.ui.SettingsDialog
import com.edge2.remote.ui.theme.Edge2
import com.edge2.remote.ui.theme.Edge2Theme

class MainActivity : ComponentActivity() {

    // Applies the chosen language (system / fr / en / es) to resources.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Settings.wrapLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Controller deep link: edge2remote://control?ws=<ws url>&pin=<code>.
        // Any website can fire this link → strictly validated URL, then explicit
        // user confirmation before any connection.
        val data = intent?.data
        val deepWs = if (data?.scheme == "edge2remote" && data.host == "control") {
            RemoteController.validateWsUrl(data.getQueryParameter("ws"))
        } else null
        val deepPin = if (deepWs != null) RemoteController.validatePin(data?.getQueryParameter("pin")) else null
        val controllerWsUrl = if (deepPin != null) deepWs else null

        setContent {
            val dark = when (Settings.theme(this)) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            var settingsOpen by remember { mutableStateOf(false) }

            Edge2Theme(darkTheme = dark) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Edge2.colors.bg)
                        .safeDrawingPadding(),
                ) {
                    if (controllerWsUrl != null) {
                        ControllerScreen(wsUrl = controllerWsUrl, pin = deepPin!!, onExit = { finish() })
                    } else {
                        App(onSettings = { settingsOpen = true })
                    }
                    if (settingsOpen) {
                        SettingsDialog(
                            lang = Settings.lang(this@MainActivity),
                            theme = Settings.theme(this@MainActivity),
                            onLang = { Settings.setLang(this@MainActivity, it); recreate() },
                            onTheme = { Settings.setTheme(this@MainActivity, it); recreate() },
                            onDismiss = { settingsOpen = false },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun App(onSettings: () -> Unit) {
    val vm: RemoteViewModel = viewModel()
    val toys by vm.toys.collectAsStateWithLifecycle()
    val scanState by vm.scanState.collectAsStateWithLifecycle()
    val discovered by vm.discovered.collectAsStateWithLifecycle()
    // Add-a-toy screen opened on top while toys are already managed.
    var adding by remember { mutableStateOf(false) }

    // Requests BLE permissions (+ notifications), then starts scanning. Denying
    // POST_NOTIFICATIONS does NOT block scanning (the notification is optional).
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

    if (toys.isNotEmpty() && !adding) {
        RemoteScreen(vm = vm, onAddToy = { adding = true }, onSettings = onSettings)
    } else {
        ConnectionScreen(
            scanState = scanState,
            discovered = discovered,
            connectedCount = toys.size,
            onScan = ::requestScan,
            onSelect = { vm.connectTo(it); adding = false },
            onBack = if (toys.isNotEmpty()) ({ vm.stopScan(); adding = false }) else null,
            onSettings = onSettings,
        )
    }
}
