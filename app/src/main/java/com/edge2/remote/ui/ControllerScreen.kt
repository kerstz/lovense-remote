package com.edge2.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edge2.remote.R
import com.edge2.remote.remote.RemoteCommand
import com.edge2.remote.remote.RemoteController
import com.edge2.remote.ui.theme.Edge2
import com.edge2.remote.ui.theme.JetBrainsMono
import kotlin.math.roundToInt

/**
 * Native CONTROLLER screen, opened by the `edge2remote://control?ws=…&pin=…` deep
 * link. The user first confirms the host (a third-party site can fire this
 * link), then drives the host's toys over WebSocket — same XY pad as the main screen.
 */
@Composable
fun ControllerScreen(wsUrl: String, pin: String, onExit: () -> Unit) {
    var confirmed by remember { mutableStateOf(false) }
    if (!confirmed) {
        val host = remember(wsUrl) { runCatching { java.net.URI(wsUrl).host }.getOrNull().orEmpty() }
        AlertDialog(
            onDismissRequest = onExit,
            confirmButton = { TextButton(onClick = { confirmed = true }) { Text(stringResource(R.string.action_connect)) } },
            dismissButton = { TextButton(onClick = onExit) { Text(stringResource(R.string.action_cancel)) } },
            title = { Text(stringResource(R.string.ctrl_confirm_title)) },
            text = { Text(stringResource(R.string.ctrl_confirm_body, host)) },
        )
        return
    }
    ControllerContent(wsUrl, pin)
}

@Composable
private fun ControllerContent(wsUrl: String, pin: String) {
    val c = Edge2.colors
    val scope = rememberCoroutineScope()
    val controller = remember { RemoteController(scope) }
    val phase by controller.phase.collectAsStateWithLifecycle()
    val toys by controller.toys.collectAsStateWithLifecycle()
    val live = phase == RemoteController.Phase.LIVE

    DisposableEffect(wsUrl) {
        controller.connect(wsUrl, pin)
        onDispose { controller.release() }
    }

    var base by remember { mutableFloatStateOf(0f) }
    var shaft by remember { mutableFloatStateOf(0f) }
    var link by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<Int?>(null) }
    // Effective target (the host's list may have shrunk).
    val target = chosen?.takeIf { it < toys.size }
    fun lvl(f: Float) = (f * 20).roundToInt()

    fun applyXY(x: Float, y: Float) {
        if (link) { val m = (x + y) / 2f; base = m; shaft = m; controller.send(RemoteCommand.SetBoth(lvl(m), target)) }
        else { base = x; shaft = y; controller.send(RemoteCommand.SetMotor(1, lvl(x), target)); controller.send(RemoteCommand.SetMotor(2, lvl(y), target)) }
    }
    fun preset(f: Float) { base = f; shaft = f; controller.send(RemoteCommand.SetBoth(lvl(f), target)) }
    fun stopAll() { base = 0f; shaft = 0f; controller.send(RemoteCommand.Stop(target)) }

    Column(
        Modifier.fillMaxSize().background(c.bg).padding(horizontal = 22.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Status banner.
        val (title, dot) = when (phase) {
            RemoteController.Phase.LIVE -> stringResource(R.string.ctrl_live) to c.live
            RemoteController.Phase.WAITING -> stringResource(R.string.ctrl_waiting) to c.muted
            RemoteController.Phase.DENIED -> stringResource(R.string.ctrl_denied) to c.danger
            RemoteController.Phase.CONNECTING -> stringResource(R.string.ctrl_connecting) to c.muted
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
                .background(dot.copy(alpha = .12f))
                .border(1.dp, dot.copy(alpha = .28f), RoundedCornerShape(15.dp))
                .padding(15.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Column(Modifier.weight(1f)) {
                Text(title, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(stringResource(R.string.ctrl_sub), color = if (live) c.live else c.muted, fontSize = 11.sp)
            }
        }

        // Host toy selector (if several).
        if (toys.size > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CtrlPreset(stringResource(R.string.toys_all), Modifier, active = target == null) { chosen = null }
                toys.forEachIndexed { i, name -> CtrlPreset(name, Modifier, active = target == i) { chosen = i } }
            }
        }

        Text(stringResource(R.string.hint_xy_generic), color = c.muted, fontSize = 11.sp)

        XYPad(
            base = base, shaft = shaft, onChange = ::applyXY, enabled = live,
            xLabel = stringResource(R.string.label_m1) + " →", yLabel = stringResource(R.string.label_m2) + " →",
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            CtrlReadout(stringResource(R.string.label_m1), c.base, (base * 100).roundToInt())
            CtrlReadout(stringResource(R.string.label_m2), c.shaft, (shaft * 100).roundToInt())
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
            CtrlLink(link) { link = !link }
            CtrlPreset(stringResource(R.string.preset_soft), Modifier.weight(1f)) { preset(0.30f) }
            CtrlPreset(stringResource(R.string.preset_medium), Modifier.weight(1f)) { preset(0.60f) }
            CtrlPreset(stringResource(R.string.preset_strong), Modifier.weight(1f)) { preset(0.90f) }
        }

        Spacer(Modifier.weight(1f))

        Text(
            stringResource(R.string.ctrl_stop_all), color = c.danger, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 2.5.sp,
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
private fun CtrlPreset(label: String, modifier: Modifier = Modifier, active: Boolean = false, onClick: () -> Unit) {
    val c = Edge2.colors
    Text(
        label, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(13.dp))
            .background(if (active) c.gradStart.copy(alpha = .18f) else c.surface.copy(alpha = if (c.isDark) .35f else 1f))
            .border(1.dp, if (active) c.gradStart else c.outline, RoundedCornerShape(13.dp))
            .clickable { onClick() }.padding(vertical = 13.dp, horizontal = 12.dp),
    )
}
