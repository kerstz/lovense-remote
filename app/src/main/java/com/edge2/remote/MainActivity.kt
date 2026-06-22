package com.edge2.remote

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edge2.remote.ble.ConnectionState
import com.edge2.remote.ble.Edge2BleManager
import com.edge2.remote.ble.Motor

/**
 * Harnais de test Phase 2 : vérifie scan/connexion/handshake et le pilotage
 * des deux moteurs. L'UI « une main » définitive arrive en Phase 3.
 */
class MainActivity : ComponentActivity() {

    private lateinit var ble: Edge2BleManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = Edge2BleManager(this)

        setContent {
            val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicDarkColorScheme(this)
            } else {
                MaterialTheme.colorScheme
            }
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RemoteScreen(ble)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ble.release()
    }
}

@Composable
private fun RemoteScreen(ble: Edge2BleManager) {
    val context = LocalContext.current
    val state by ble.connectionState.collectAsStateWithLifecycle()

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) ble.connect()
    }

    fun requestAndConnect() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissionLauncher.launch(perms)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "Edge2 Remote", style = MaterialTheme.typography.headlineMedium)
        Text(text = statusLabel(state), style = MaterialTheme.typography.bodyLarge)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { requestAndConnect() }) { Text("Connecter") }
            OutlinedButton(onClick = { ble.disconnect() }) { Text("Déconnecter") }
        }

        Spacer(Modifier.height(8.dp))

        val enabled = state is ConnectionState.Connected
        MotorSlider("Moteur base (Vibrate1)", enabled) { ble.setMotor(Motor.BASE, it) }
        MotorSlider("Moteur tige (Vibrate2)", enabled) { ble.setMotor(Motor.SHAFT, it) }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { ble.stopAll() },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("STOP") }
    }
}

@Composable
private fun MotorSlider(label: String, enabled: Boolean, onChange: (Float) -> Unit) {
    var value by remember { mutableFloatStateOf(0f) }
    Column {
        Text("$label — ${(value * 20).toInt()}/20")
        Slider(
            value = value,
            onValueChange = { value = it; onChange(it) },
            enabled = enabled,
        )
    }
}

private fun statusLabel(state: ConnectionState): String = when (state) {
    is ConnectionState.Disconnected -> "Déconnecté"
    is ConnectionState.Scanning -> "Recherche du toy…"
    is ConnectionState.Connecting -> "Connexion…"
    is ConnectionState.Connected ->
        "Connecté : ${state.deviceName}" + (state.battery?.let { " — batterie $it%" } ?: "")
    is ConnectionState.Error -> "Erreur : ${state.reason}"
}
