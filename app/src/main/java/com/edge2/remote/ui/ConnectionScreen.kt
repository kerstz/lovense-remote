package com.edge2.remote.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edge2.remote.ble.ConnectionState
import com.edge2.remote.ui.theme.Edge2
import com.edge2.remote.ui.theme.JetBrainsMono

/** Trace l'éclair Lovense `M7 7l10 10-5 5V2l5 5L7 17` centré, échelle [s] px/unité. */
private fun DrawScope.drawBolt(center: Offset, s: Float, color: Color) {
    val pts = listOf(7f to 7f, 17f to 17f, 12f to 22f, 12f to 2f, 17f to 7f, 7f to 17f)
    val path = Path()
    pts.forEachIndexed { i, (x, y) ->
        val px = center.x + (x - 12f) * s
        val py = center.y + (y - 12f) * s
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    drawPath(path, color, style = Stroke(width = s * 1.9f, join = StrokeJoin.Round))
}

/** Orbe gradient + anneaux pulsés (signature de l'écran connexion / invitation). */
@Composable
fun PulseOrb(active: Boolean, modifier: Modifier = Modifier) {
    val c = Edge2.colors
    val tr = rememberInfiniteTransition(label = "orb")
    val rings = listOf(0, 900, 1800).map { delay ->
        tr.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), initialStartOffset = StartOffset(delay)),
            label = "ring$delay",
        )
    }
    androidx.compose.foundation.Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxR = size.minDimension / 2f
        if (active) rings.forEachIndexed { i, p ->
            val f = p.value
            val rr = maxR * (0.41f + 0.59f * f)
            val alpha = 0.55f * (1f - f)
            drawCircle(
                (if (i == 1) c.tige else c.gradStart).copy(alpha = alpha),
                radius = rr, center = center, style = Stroke(1.5.dp.toPx()),
            )
        }
        val coreR = maxR * 0.53f
        drawCircle(
            Brush.radialGradient(0f to c.gradStart.copy(alpha = .5f), 1f to Color.Transparent, center = center, radius = coreR * 1.9f),
            radius = coreR * 1.9f, center = center,
        )
        drawCircle(Brush.linearGradient(listOf(c.gradStart, c.gradEnd)), radius = coreR, center = center)
        drawBolt(center, coreR * 0.5f / 6f, Color.White)
    }
}

/**
 * Écran connexion : orbe pulsé + carte appareil. Câblé au flux réel — un seul
 * geste lance scan→connexion (le BLE choisit le premier toy `LVS-…`).
 */
@Composable
fun ConnectionScreen(state: ConnectionState, onConnect: () -> Unit) {
    val c = Edge2.colors
    val scanning = state is ConnectionState.Scanning
    val connecting = state is ConnectionState.Connecting
    val busy = scanning || connecting
    val error = state as? ConnectionState.Error

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            .padding(horizontal = 26.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                    .background(Brush.linearGradient(listOf(c.gradStart, c.gradEnd))),
            )
            Text("EDGE 2 · TÉLÉCOMMANDE", color = c.muted, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 3.sp)
        }

        Spacer(Modifier.size(8.dp))
        Text("Connecter le jouet", color = c.ink, fontWeight = FontWeight.Bold, fontSize = 26.sp)

        PulseOrb(active = true, modifier = Modifier.size(160.dp).align(Alignment.CenterHorizontally))

        if (scanning) {
            Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(c.live))
                Spacer(Modifier.size(8.dp))
                Text("Recherche d'appareils à proximité…", color = c.muted, fontSize = 12.sp)
            }
        }

        // Carte appareil — déclenche scan→connexion.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(c.gradStart.copy(alpha = .12f))
                .border(1.dp, c.gradStart.copy(alpha = .42f), RoundedCornerShape(18.dp))
                .clickable(enabled = !busy) { onConnect() }
                .padding(15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(c.gradStart, c.gradEnd))),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
                    drawBolt(Offset(size.width / 2f, size.height / 2f), size.minDimension / 2f / 9f, Color.White)
                }
            }
            Column(Modifier.weight(1f)) {
                Text("Edge 2", color = c.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    error?.reason ?: "Bluetooth LE direct · sans app Lovense",
                    color = if (error != null) c.danger else c.muted,
                    fontFamily = JetBrainsMono, fontSize = 11.sp,
                )
            }
            when {
                busy -> CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 3.dp)
                else -> Text(
                    "Appairer", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(11.dp)).background(c.gradStart).padding(horizontal = 15.dp, vertical = 9.dp),
                )
            }
        }

        Spacer(Modifier.weight(1f))
        Text(
            "Active le Bluetooth et approche le jouet.\nAppui long 3 s sur le bouton pour l'allumer.",
            color = c.faint, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}
