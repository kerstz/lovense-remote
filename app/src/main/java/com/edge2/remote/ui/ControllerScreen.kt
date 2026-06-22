package com.edge2.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edge2.remote.remote.RemoteCommand
import com.edge2.remote.remote.RemoteController
import com.edge2.remote.ui.theme.Edge2
import com.edge2.remote.ui.theme.JetBrainsMono
import kotlin.math.roundToInt

/**
 * Écran CONTRÔLEUR natif : ouvert par deep link `edge2remote://control?ws=…`.
 * Pilote le toy d'un host distant via WebSocket — même pad XY que l'écran principal.
 */
@Composable
fun ControllerScreen(wsUrl: String) {
    val c = Edge2.colors
    val scope = rememberCoroutineScope()
    val controller = remember { RemoteController(scope) }
    val connected by controller.connected.collectAsStateWithLifecycle()

    DisposableEffect(wsUrl) {
        controller.connect(wsUrl)
        onDispose { controller.release() }
    }

    var base by remember { mutableFloatStateOf(0f) }
    var tige by remember { mutableFloatStateOf(0f) }
    var link by remember { mutableStateOf(false) }
    fun lvl(f: Float) = (f * 20).roundToInt()

    fun applyXY(x: Float, y: Float) {
        if (link) { val m = (x + y) / 2f; base = m; tige = m; controller.send(RemoteCommand.SetBoth(lvl(m))) }
        else { base = x; tige = y; controller.send(RemoteCommand.SetMotor(1, lvl(x))); controller.send(RemoteCommand.SetMotor(2, lvl(y))) }
    }
    fun preset(f: Float) { base = f; tige = f; controller.send(RemoteCommand.SetBoth(lvl(f))) }
    fun stopAll() { base = 0f; tige = 0f; controller.send(RemoteCommand.Stop) }

    Column(
        Modifier.fillMaxSize().background(c.bg).padding(horizontal = 22.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Bannière « en direct ».
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
                .background(c.live.copy(alpha = .12f))
                .border(1.dp, c.live.copy(alpha = .28f), RoundedCornerShape(15.dp))
                .padding(15.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (connected) c.live else c.muted))
            Column(Modifier.weight(1f)) {
                Text(if (connected) "En direct · tu as le contrôle" else "Connexion au toy distant…", color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("Contrôle à distance de l'Edge 2", color = if (connected) c.live else c.muted, fontSize = 11.sp)
            }
        }

        Text("Glisse le point — horizontal = base · vertical = tige", color = c.muted, fontSize = 11.sp)

        XYPad(base = base, tige = tige, onChange = ::applyXY, enabled = connected, modifier = Modifier.fillMaxWidth().aspectRatio(1f))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            CtrlReadout("BASE", c.base, (base * 100).roundToInt())
            CtrlReadout("TIGE", c.tige, (tige * 100).roundToInt())
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
            CtrlLink(link) { link = !link }
            CtrlPreset("Doux", Modifier.weight(1f)) { preset(0.30f) }
            CtrlPreset("Moyen", Modifier.weight(1f)) { preset(0.60f) }
            CtrlPreset("Fort", Modifier.weight(1f)) { preset(0.90f) }
        }

        Spacer(Modifier.weight(1f))

        Text(
            "TOUT ARRÊTER", color = c.danger, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 2.5.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
                .background(c.danger.copy(alpha = .10f))
                .border(1.dp, c.danger.copy(alpha = .28f), RoundedCornerShape(15.dp))
                .clickable { stopAll() }.padding(vertical = 16.dp),
        )
    }
}

@Composable
private fun RowScope.CtrlReadout(label: String, accent: Color, value: Int) {
    val c = Edge2.colors
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(15.dp))
            .background(accent.copy(alpha = .08f))
            .border(1.dp, accent.copy(alpha = .25f), RoundedCornerShape(15.dp))
            .padding(horizontal = 15.dp, vertical = 11.dp),
    ) {
        Text(label, color = accent, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 2.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$value", color = c.ink, fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 26.sp)
            Text("%", color = c.muted, fontFamily = JetBrainsMono, fontSize = 13.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

@Composable
private fun CtrlLink(active: Boolean, onClick: () -> Unit) {
    val c = Edge2.colors
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(13.dp))
            .background(if (active) c.gradStart else c.surface.copy(alpha = if (c.isDark) .35f else 1f))
            .border(1.dp, if (active) c.gradStart else c.outline, RoundedCornerShape(13.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
            val fg = if (active) Color.White else c.muted
            val r = size.minDimension * .26f; val cy = size.height / 2f
            drawCircle(fg, radius = r, center = androidx.compose.ui.geometry.Offset(size.width * .36f, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            drawCircle(fg, radius = r, center = androidx.compose.ui.geometry.Offset(size.width * .64f, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
    }
}

@Composable
private fun CtrlPreset(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Edge2.colors
    Text(
        label, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, textAlign = TextAlign.Center,
        modifier = modifier.clip(RoundedCornerShape(13.dp))
            .background(c.surface.copy(alpha = if (c.isDark) .35f else 1f))
            .border(1.dp, c.outline, RoundedCornerShape(13.dp))
            .clickable { onClick() }.padding(vertical = 13.dp),
    )
}
