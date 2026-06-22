package com.edge2.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.edge2.remote.remote.NetworkUtils
import com.edge2.remote.RemoteViewModel
import com.edge2.remote.ble.ConnectionState
import com.edge2.remote.ble.Motor
import com.edge2.remote.pattern.BuiltinPatterns
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Télécommande une main : statut en haut, presets + patterns au milieu, deux
 * gros sliders verticaux + STOP en bas (zone du pouce).
 *
 * [onRequestConnect] délègue à l'Activity la demande de permissions avant scan.
 */
@Composable
fun RemoteScreen(vm: RemoteViewModel, onRequestConnect: () -> Unit) {
    val state by vm.connectionState.collectAsStateWithLifecycle()
    val link by vm.linkMode.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val shareUrl by vm.shareUrl.collectAsStateWithLifecycle()

    val connected = state is ConnectionState.Connected
    var shareOpen by remember { mutableStateOf(false) }

    // État local des sliders (source de vérité du geste manuel).
    var baseF by remember { mutableFloatStateOf(0f) }
    var shaftF by remember { mutableFloatStateOf(0f) }

    fun applyBase(f: Float) {
        baseF = f
        if (link) { shaftF = f; vm.setBoth(f) } else vm.setMotor(Motor.BASE, f)
    }
    fun applyShaft(f: Float) {
        shaftF = f
        if (link) { baseF = f; vm.setBoth(f) } else vm.setMotor(Motor.SHAFT, f)
    }
    fun applyBoth(f: Float) { baseF = f; shaftF = f; vm.setBoth(f) }
    fun stopAll() { baseF = 0f; shaftF = 0f; vm.stopAll() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // --- Statut + connexion -----------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Edge2 Remote", style = MaterialTheme.typography.titleMedium)
                Text(statusLabel(state), style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (connected) {
                    TextButton(onClick = { vm.startSharing(); shareOpen = true }) { Text("Partager") }
                    OutlinedButton(onClick = { vm.disconnect() }) { Text("Couper") }
                } else {
                    Button(onClick = onRequestConnect) { Text("Connecter") }
                }
            }
        }

        if (shareOpen) {
            ShareDialog(
                url = shareUrl,
                onDismiss = { vm.stopSharing(); shareOpen = false },
            )
        }

        // --- Presets d'intensité ----------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(0 to "0", 5 to "25%", 10 to "50%", 15 to "75%", 20 to "100%").forEach { (lvl, lab) ->
                AssistChip(
                    onClick = { applyBoth(lvl / 20f) },
                    label = { Text(lab) },
                    enabled = connected,
                )
            }
        }

        // --- Patterns + Link --------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BuiltinPatterns.all.forEach { pattern ->
                FilterChip(
                    selected = playing == pattern.name,
                    onClick = {
                        if (playing == pattern.name) stopAll() else vm.playPattern(pattern)
                    },
                    label = { Text(pattern.name) },
                    enabled = connected,
                )
            }
            Spacer(Modifier.fillMaxWidth(0.001f))
            FilterChip(
                selected = link,
                onClick = { vm.toggleLink() },
                label = { Text("Link") },
                enabled = connected,
            )
        }

        // --- Deux gros sliders verticaux (zone pouce) -------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            VerticalSlider(
                fraction = baseF,
                onFraction = ::applyBase,
                label = "BASE",
                enabled = connected,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            VerticalSlider(
                fraction = shaftF,
                onFraction = ::applyShaft,
                label = "TIGE",
                enabled = connected,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }

        // --- STOP --------------------------------------------------------
        Button(
            onClick = { stopAll() },
            enabled = connected,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            Text("STOP", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ShareDialog(url: String?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Arrêter le partage") } },
        title = { Text("Contrôle à distance") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (url == null) {
                    Text("Aucun réseau WiFi détecté. Connecte le téléphone à un WiFi pour partager en LAN (le tunnel internet arrive en 4B).")
                } else {
                    Text("Sur le MÊME WiFi, ouvre ce lien (ou scanne le QR) :")
                    Text(url, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    val qr = remember(url) { NetworkUtils.qrBitmap(url, 480).asImageBitmap() }
                    Image(
                        bitmap = qr,
                        contentDescription = "QR du lien de contrôle",
                        modifier = Modifier
                            .width(220.dp)
                            .height(220.dp),
                    )
                    Text(
                        "Tant que ce partage est ouvert, qui a le lien pilote le toy.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
    )
}

private fun statusLabel(state: ConnectionState): String = when (state) {
    is ConnectionState.Disconnected -> "Déconnecté"
    is ConnectionState.Scanning -> "Recherche…"
    is ConnectionState.Connecting -> "Connexion…"
    is ConnectionState.Connected ->
        state.deviceName + (state.battery?.let { " · $it%" } ?: "")
    is ConnectionState.Error -> "Erreur : ${state.reason}"
}
