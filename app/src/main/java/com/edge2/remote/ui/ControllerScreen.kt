package com.edge2.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edge2.remote.remote.RemoteCommand
import com.edge2.remote.remote.RemoteController
import kotlin.math.roundToInt

/**
 * Écran CONTRÔLEUR : ouvert par un deep link `edge2remote://control?ws=…`.
 * Pilote le toy d'un host distant via WebSocket (mêmes gros sliders).
 */
@Composable
fun ControllerScreen(wsUrl: String) {
    val scope = rememberCoroutineScope()
    val controller = remember { RemoteController(scope) }
    val connected by controller.connected.collectAsStateWithLifecycle()

    DisposableEffect(wsUrl) {
        controller.connect(wsUrl)
        onDispose { controller.release() }
    }

    var baseF by remember { mutableFloatStateOf(0f) }
    var shaftF by remember { mutableFloatStateOf(0f) }
    fun lvl(f: Float) = (f * 20).roundToInt()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Contrôle à distance", style = MaterialTheme.typography.titleMedium)
        Text(
            if (connected) "Connecté au toy distant" else "Connexion…",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            VerticalSlider(
                fraction = baseF,
                onFraction = { baseF = it; controller.send(RemoteCommand.SetMotor(1, lvl(it))) },
                label = "BASE",
                enabled = connected,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            VerticalSlider(
                fraction = shaftF,
                onFraction = { shaftF = it; controller.send(RemoteCommand.SetMotor(2, lvl(it))) },
                label = "TIGE",
                enabled = connected,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }

        Button(
            onClick = { baseF = 0f; shaftF = 0f; controller.send(RemoteCommand.Stop) },
            enabled = connected,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            Text("STOP", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}
