package com.edge2.remote.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edge2.remote.R
import com.edge2.remote.RemoteViewModel
import com.edge2.remote.ShareError
import com.edge2.remote.ble.ActuatorKind
import com.edge2.remote.ble.Brand
import com.edge2.remote.ble.LinkState
import com.edge2.remote.ble.ToyStatus
import com.edge2.remote.pattern.BuiltinPatterns
import com.edge2.remote.pattern.PatternPlayer
import com.edge2.remote.remote.NetworkUtils
import com.edge2.remote.ui.theme.Edge2
import com.edge2.remote.ui.theme.JetBrainsMono
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Main screen — **multi-toy**: a selector (All / each toy / + Toy) picks the
 * target; controls adapt to the targeted toy (XY pad for 2 vibrators, else one
 * slider per actuator), or a master slider for "All". Patterns and presets
 * follow the current target; STOP always stops EVERYTHING.
 */
@Composable
fun RemoteScreen(vm: RemoteViewModel, onAddToy: () -> Unit, onSettings: () -> Unit = {}) {
    val c = Edge2.colors
    val toys by vm.toys.collectAsStateWithLifecycle()
    val targetAddr by vm.target.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val controllers by vm.controllers.collectAsStateWithLifecycle()
    val sharing by vm.sharing.collectAsStateWithLifecycle()
    val pin by vm.pin.collectAsStateWithLifecycle()
    val shareUrl by vm.shareUrl.collectAsStateWithLifecycle()
    val tunnelUrl by vm.tunnelUrl.collectAsStateWithLifecycle()
    val tunnelPreparing by vm.tunnelPreparing.collectAsStateWithLifecycle()
    val hostKeyMismatch by vm.tunnelHostKeyMismatch.collectAsStateWithLifecycle()
    val shareError by vm.shareError.collectAsStateWithLifecycle()
    val imported by vm.importedPatterns.collectAsStateWithLifecycle()

    // Displayed toy: the chosen target, or the only toy if there is just one.
    val selected: ToyStatus? = toys.firstOrNull { it.address == targetAddr } ?: toys.singleOrNull()
    var shareOpen by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }
    val approvedCount = controllers.count { it.approved }
    val pending = controllers.firstOrNull { !it.approved }

    // As soon as a remote controller is approved → close the share dialog.
    LaunchedEffect(approvedCount) { if (approvedCount > 0) shareOpen = false }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        // --- Header: target + status + battery + actions -----------------
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(
                    selected?.displayName ?: stringResource(R.string.toys_count, toys.size),
                    color = c.ink, fontWeight = FontWeight.Bold, fontSize = 21.sp, maxLines = 1,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val ready = selected?.isReady ?: toys.any { it.isReady }
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (ready) c.live else c.muted))
                    Text(
                        selected?.let { linkLabel(it) } ?: stringResource(R.string.status_connected),
                        color = if (ready) c.live else c.muted, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                val battery = selected?.battery
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BatteryGlyph(battery)
                    Text(battery?.let { stringResource(R.string.battery_fmt, it) } ?: "BLE", color = c.muted, fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 11.sp)
                }
                Spacer(Modifier.size(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GhostChip("⚙") { onSettings() }
                    GhostChip(stringResource(R.string.action_share)) { vm.startSharing(); shareOpen = true }
                    GhostChip(stringResource(R.string.action_disconnect)) {
                        // A targeted toy → remove that one; otherwise disconnect everything.
                        val t = toys.firstOrNull { it.address == targetAddr }
                        if (t != null) vm.disconnect(t.address) else vm.disconnectAll()
                    }
                }
            }
        }

        // --- Toy selector ------------------------------------------------
        ToySelector(toys, targetAddr, onSelect = vm::selectTarget, onAdd = onAddToy)

        // --- "You're being controlled": a partner is driving remotely ----
        if (approvedCount > 0) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
                    .background(c.live.copy(alpha = .12f))
                    .border(1.dp, c.live.copy(alpha = .35f), RoundedCornerShape(15.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(c.live))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.controlled_title), color = c.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(stringResource(R.string.controlled_count, approvedCount), color = c.live, fontSize = 11.sp)
                }
                GhostChip(stringResource(R.string.share_stop)) { vm.stopSharing() }
            }
        }

        // --- Controls adapted to the target --------------------------------
        when {
            selected == null -> AllToysControls(vm, toys)
            !selected.isReady -> ToyNotReady(selected) { vm.disconnect(selected.address) }
            selected.toy.isDualVibrate -> DualVibrateControls(vm, selected, playing != null)
            else -> ActuatorControls(vm, selected, playing != null)
        }

        // --- Patterns (follow the target: one toy or all) ----------------
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.patterns_header), color = c.faint, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 2.5.sp)
            if (playing != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.clickable { vm.stopAll() },
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(c.live))
                    Text(stringResource(R.string.playing_stop), color = c.live, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 1.sp)
                }
            }
        }
        (BuiltinPatterns.all + imported + listOf(null)).chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { i, p ->
                    if (p == null) {
                        PatternChip("+ Lovense", -1, false, Modifier.weight(1f)) { importOpen = true }
                    } else {
                        PatternChip(p.name, i, playing == p.name, Modifier.weight(1f)) {
                            if (playing == p.name) vm.stopAll() else vm.playPattern(p)
                        }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        // Tease (random) + Record (perform → custom pattern).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PatternChip(stringResource(R.string.pattern_tease), 0, playing == PatternPlayer.TEASE, Modifier.weight(1f)) {
                if (playing == PatternPlayer.TEASE) vm.stopAll() else vm.playTease()
            }
            val recLabel = if (recording) stringResource(R.string.pattern_recording) else stringResource(R.string.pattern_record)
            PatternChip(recLabel, 2, recording, Modifier.weight(1f)) {
                if (recording) vm.stopRecording() else vm.startRecording()
            }
            Spacer(Modifier.weight(1f))
        }

        // --- Giant STOP: while sharing or with several toys --------------
        if (sharing || toys.size > 1) {
            Text(
                stringResource(R.string.ctrl_stop_all),
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 2.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(c.danger).clickable { vm.stopAll() }.padding(vertical = 16.dp),
            )
        }
    }

    if (shareOpen) {
        ShareDialog(
            lanUrl = shareUrl, tunnelUrl = tunnelUrl, tunnelPreparing = tunnelPreparing,
            hostKeyMismatch = hostKeyMismatch, shareError = shareError, pin = pin,
            onTrustKey = vm::trustNewRelayKey,
            onStop = { vm.stopSharing(); shareOpen = false },
            onClose = { shareOpen = false },
        )
    }
    // Approval request PER authenticated controller (before it can drive).
    if (sharing && pending != null) {
        AlertDialog(
            onDismissRequest = { },
            confirmButton = { TextButton(onClick = { vm.approveController(pending.id) }) { Text(stringResource(R.string.action_accept)) } },
            dismissButton = { TextButton(onClick = { vm.refuseController(pending.id) }) { Text(stringResource(R.string.action_refuse)) } },
            title = { Text(stringResource(R.string.approve_title)) },
            text = { Text(stringResource(R.string.approve_body)) },
        )
    }
    if (importOpen) {
        ImportDialog(
            onImportUrl = { vm.importFromUrl(it); importOpen = false },
            onImportText = { vm.importFromText(it); importOpen = false },
            onDismiss = { importOpen = false },
        )
    }
}

@Composable
private fun linkLabel(t: ToyStatus): String = when (val l = t.link) {
    LinkState.Connected -> stringResource(R.string.status_connected)
    LinkState.Connecting -> stringResource(R.string.toy_connecting)
    LinkState.Reconnecting -> stringResource(R.string.toy_reconnecting)
    is LinkState.Error -> stringResource(R.string.toy_error, l.reason)
}

/** Selection chips: "All" (if ≥ 2 toys), one per toy, and "+ Toy". */
@Composable
private fun ToySelector(toys: List<ToyStatus>, target: String?, onSelect: (String?) -> Unit, onAdd: () -> Unit) {
    val c = Edge2.colors
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        @Composable
        fun chip(label: String, active: Boolean, dot: Color?, onClick: () -> Unit) {
            Row(
                Modifier.clip(RoundedCornerShape(12.dp))
                    .background(if (active) c.gradStart.copy(alpha = .18f) else c.surface.copy(alpha = if (c.isDark) .35f else 1f))
                    .border(1.dp, if (active) c.gradStart else c.outline, RoundedCornerShape(12.dp))
                    .clickable { onClick() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (dot != null) Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
                Text(label, color = if (active) c.ink else c.muted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1)
            }
        }
        if (toys.size > 1) chip(stringResource(R.string.toys_all), target == null, null) { onSelect(null) }
        toys.forEach { t ->
            val dot = when (t.link) {
                LinkState.Connected -> c.live
                is LinkState.Error -> c.danger
                else -> c.muted
            }
            val label = t.displayName + (t.battery?.let { " · $it%" } ?: "")
            chip(label, toys.size > 1 && target == t.address, dot) { onSelect(t.address) }
        }
        chip(stringResource(R.string.toys_add), false, null) { onAdd() }
    }
}

/** Toy connecting / in error: status + Remove button. */
@Composable
private fun ToyNotReady(t: ToyStatus, onRemove: () -> Unit) {
    val c = Edge2.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
            .border(1.dp, c.outline, RoundedCornerShape(15.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(linkLabel(t), color = if (t.link is LinkState.Error) c.danger else c.muted, fontSize = 13.sp)
        GhostChip(stringResource(R.string.action_remove)) { onRemove() }
    }
}

/** "All" target: master slider applied to every actuator of every toy. */
@Composable
private fun AllToysControls(vm: RemoteViewModel, toys: List<ToyStatus>) {
    val c = Edge2.colors
    var master by remember { mutableFloatStateOf(0f) }
    Text(stringResource(R.string.hint_all), color = c.muted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth())
    ActuatorSlider(
        label = stringResource(R.string.label_all), accent = c.base, fraction = master,
        percent = (master * 100).roundToInt(), reversible = false, onReverse = {},
        onChange = { f -> master = f; vm.setAll(null, f) },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        fun all(f: Float) { master = f; vm.setAll(null, f) }
        PresetButton(stringResource(R.string.preset_soft), Modifier.weight(1f)) { all(0.30f) }
        PresetButton(stringResource(R.string.preset_medium), Modifier.weight(1f)) { all(0.60f) }
        PresetButton(stringResource(R.string.preset_strong), Modifier.weight(1f)) { all(0.90f) }
    }
    // Reminder of each toy's state (level of its first actuator).
    toys.forEach { t ->
        val max = t.toy.actuators.firstOrNull()?.max ?: 1
        val pct = ((t.levels.firstOrNull() ?: 0) * 100f / max).roundToInt()
        Text("${t.displayName} · ${linkLabel(t)} · $pct%", color = c.faint, fontSize = 11.sp, fontFamily = JetBrainsMono)
    }
}

/** Two-vibrator toys (Edge, Gemini, We-Vibe Sync…): XY pad + readouts + Link + presets. */
@Composable
private fun DualVibrateControls(vm: RemoteViewModel, t: ToyStatus, playing: Boolean) {
    val c = Edge2.colors
    val link by vm.linkMode.collectAsStateWithLifecycle()
    // Manual drag = source of truth; during a pattern, follow the real motors.
    var localBase by remember(t.address) { mutableFloatStateOf(0f) }
    var localShaft by remember(t.address) { mutableFloatStateOf(0f) }
    val m1 = t.toy.actuators[0].max.toFloat()
    val m2 = t.toy.actuators[1].max.toFloat()
    val baseF = if (playing) t.levels.getOrElse(0) { 0 } / m1 else localBase
    val shaftF = if (playing) t.levels.getOrElse(1) { 0 } / m2 else localShaft
    // BASE/SHAFT labels are Edge-specific; generic otherwise.
    val edge = t.toy.brand == Brand.LOVENSE && t.toy.code == "P"
    val xLabel = stringResource(if (edge) R.string.label_base else R.string.label_m1)
    val yLabel = stringResource(if (edge) R.string.label_shaft else R.string.label_m2)

    fun applyXY(x: Float, y: Float) {
        if (link) { val m = (x + y) / 2f; localBase = m; localShaft = m; vm.setXY(t.address, m, m) }
        else { localBase = x; localShaft = y; vm.setXY(t.address, x, y) }
    }
    fun preset(f: Float) { localBase = f; localShaft = f; vm.setAll(t.address, f) }

    Text(
        if (edge) stringResource(R.string.hint_xy) else stringResource(R.string.hint_xy_generic),
        color = c.muted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth(),
    )
    XYPad(
        base = baseF, shaft = shaftF, onChange = ::applyXY,
        xLabel = "$xLabel →", yLabel = "$yLabel →",
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Readout(xLabel, c.base, (baseF * 100).roundToInt())
        Readout(yLabel, c.shaft, (shaftF * 100).roundToInt())
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        LinkToggle(link) { vm.toggleLink() }
        PresetButton(stringResource(R.string.preset_soft), Modifier.weight(1f)) { preset(0.30f) }
        PresetButton(stringResource(R.string.preset_medium), Modifier.weight(1f)) { preset(0.60f) }
        PresetButton(stringResource(R.string.preset_strong), Modifier.weight(1f)) { preset(0.90f) }
    }
}

/** Other toys: one labelled slider per actuator (+ direction button for rotation). */
@Composable
private fun ActuatorControls(vm: RemoteViewModel, t: ToyStatus, playing: Boolean) {
    val c = Edge2.colors
    val toy = t.toy
    val local = remember(t.address, toy) { mutableStateListOf<Float>().apply { repeat(toy.actuators.size) { add(0f) } } }

    Text(stringResource(R.string.hint_actuators, toy.displayName), color = c.muted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth())
    toy.actuators.forEachIndexed { i, act ->
        val accent = when (act.kind) {
            ActuatorKind.VIBRATE -> if (i == 0) c.base else c.shaft
            ActuatorKind.ROTATE -> c.shaft
            ActuatorKind.SUCTION -> c.live
        }
        val value = if (playing && i < t.levels.size) t.levels[i] / act.max.toFloat() else local.getOrElse(i) { 0f }
        ActuatorSlider(
            label = kindLabel(act.kind), accent = accent, fraction = value, percent = (value * 100).roundToInt(),
            reversible = act.reversible, onReverse = { vm.reverse(t.address, i) },
            onChange = { f -> if (i < local.size) local[i] = f; vm.setActuator(t.address, i, f) },
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        fun all(f: Float) { for (i in local.indices) local[i] = f; vm.setAll(t.address, f) }
        PresetButton(stringResource(R.string.preset_soft), Modifier.weight(1f)) { all(0.30f) }
        PresetButton(stringResource(R.string.preset_medium), Modifier.weight(1f)) { all(0.60f) }
        PresetButton(stringResource(R.string.preset_strong), Modifier.weight(1f)) { all(0.90f) }
    }
}

/** Localized label of an actuator type. */
@Composable
private fun kindLabel(kind: ActuatorKind): String = stringResource(
    when (kind) {
        ActuatorKind.VIBRATE -> R.string.kind_vibrate
        ActuatorKind.ROTATE -> R.string.kind_rotate
        ActuatorKind.SUCTION -> R.string.kind_air
    },
)

@Composable
private fun ActuatorSlider(
    label: String,
    accent: Color,
    fraction: Float,
    percent: Int,
    reversible: Boolean,
    onReverse: () -> Unit,
    onChange: (Float) -> Unit,
) {
    val c = Edge2.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
            .background(accent.copy(alpha = .08f))
            .border(1.dp, accent.copy(alpha = .25f), RoundedCornerShape(15.dp))
            .padding(horizontal = 15.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), color = accent, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 2.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (reversible) {
                    Text(
                        stringResource(R.string.actuator_reverse), color = accent, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
                        modifier = Modifier.clip(RoundedCornerShape(9.dp)).border(1.dp, accent.copy(alpha = .4f), RoundedCornerShape(9.dp))
                            .clickable { onReverse() }.padding(horizontal = 9.dp, vertical = 4.dp),
                    )
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$percent", color = c.ink, fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("%", color = c.muted, fontFamily = JetBrainsMono, fontSize = 12.sp, modifier = Modifier.padding(bottom = 2.dp))
                }
            }
        }
        Slider(
            value = fraction.coerceIn(0f, 1f), onValueChange = onChange,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = c.outline),
        )
    }
}

@Composable
private fun RowScope.Readout(label: String, accent: Color, value: Int) {
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
private fun PatternChip(label: String, index: Int, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Edge2.colors
    val border = if (active) c.gradStart else c.outline
    val fg = if (active) c.ink else c.muted
    Column(
        modifier
            .clip(RoundedCornerShape(13.dp))
            .background(if (active) c.gradStart.copy(alpha = .16f) else c.surface.copy(alpha = if (c.isDark) .35f else 1f))
            .border(1.dp, border, RoundedCornerShape(13.dp))
            .clickable { onClick() }
            .padding(vertical = 9.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Canvas(Modifier.size(width = 34.dp, height = 14.dp)) { drawWave(index, fg) }
        Text(label, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, maxLines = 1)
    }
}

/** Small wave: sine / square / ramp depending on the index (purely decorative). */
private fun DrawScope.drawWave(index: Int, color: Color) {
    val w = size.width; val h = size.height; val mid = h / 2f
    val path = androidx.compose.ui.graphics.Path()
    val steps = 28
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val x = t * w
        val y = when (index) {
            1 -> mid - (if ((t * 4).toInt() % 2 == 0) h * .38f else -h * .38f) // square (Pulse)
            2 -> h - t * h                                                      // ramp (Ramp)  
            else -> mid - sin(t * 2 * Math.PI * 2).toFloat() * h * .38f         // sine
        }
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round))
}

@Composable
private fun LinkToggle(active: Boolean, onClick: () -> Unit) {
    val c = Edge2.colors
    val fg = if (active) Color.White else c.muted
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(13.dp))
            .background(if (active) c.gradStart else c.surface.copy(alpha = if (c.isDark) .35f else 1f))
            .border(1.dp, if (active) c.gradStart else c.outline, RoundedCornerShape(13.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val r = size.minDimension * .26f
            val cy = size.height / 2f
            drawCircle(fg, radius = r, center = Offset(size.width * .36f, cy), style = Stroke(2.dp.toPx()))
            drawCircle(fg, radius = r, center = Offset(size.width * .64f, cy), style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
private fun PresetButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Edge2.colors
    Text(
        label, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .background(c.surface.copy(alpha = if (c.isDark) .35f else 1f))
            .border(1.dp, c.outline, RoundedCornerShape(13.dp))
            .clickable { onClick() }
            .padding(vertical = 13.dp),
    )
}

@Composable
private fun GhostChip(label: String, onClick: () -> Unit) {
    val c = Edge2.colors
    Text(
        label, color = c.muted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, c.outline, RoundedCornerShape(10.dp))
            .clickable { onClick() }.padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

@Composable
private fun BatteryGlyph(level: Int?) {
    val c = Edge2.colors
    Canvas(Modifier.size(width = 26.dp, height = 13.dp)) {
        val bodyW = size.width * .88f
        val r = 3.dp.toPx()
        drawRoundRect(
            c.muted.copy(alpha = .35f),
            size = androidx.compose.ui.geometry.Size(bodyW, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
            style = Stroke(1.5.dp.toPx()),
        )
        drawRoundRect(
            c.muted.copy(alpha = .35f),
            topLeft = Offset(bodyW + 1.dp.toPx(), size.height * .28f),
            size = androidx.compose.ui.geometry.Size(2.5.dp.toPx(), size.height * .44f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
        )
        val pad = 2.dp.toPx()
        val fillW = ((bodyW - pad * 2) * ((level ?: 0) / 100f)).coerceAtLeast(0f)
        drawRoundRect(
            c.live,
            topLeft = Offset(pad, pad),
            size = androidx.compose.ui.geometry.Size(fillW, size.height - pad * 2),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx()),
        )
    }
}

// --- Dialogs (sharing + import) — inherit the Edge2 theme ----------------

@Composable
private fun ShareDialog(
    lanUrl: String?,
    tunnelUrl: String?,
    tunnelPreparing: Boolean,
    hostKeyMismatch: Boolean,
    shareError: ShareError,
    pin: String?,
    onTrustKey: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    val c = Edge2.colors
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onStop) { Text(stringResource(R.string.share_stop)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) } },
        title = { Text(stringResource(R.string.share_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (shareError) {
                    ShareError.BLOCKED -> Text(stringResource(R.string.share_blocked), color = c.danger)
                    ShareError.LOCKED -> Text(stringResource(R.string.share_locked), color = c.danger)
                    ShareError.NONE -> {
                        if (pin != null) {
                            Text(
                                stringResource(R.string.pin_label, pin),
                                color = c.base, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                                fontFamily = JetBrainsMono,
                            )
                        }
                        // Internet (SSH/localhost.run) — works from anywhere, 4G included.
                        when {
                            hostKeyMismatch -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.share_hostkey), color = c.danger)
                                GhostChip(stringResource(R.string.action_trust)) { onTrustKey() }
                            }
                            tunnelUrl != null -> ShareBlock(
                                stringResource(R.string.share_internet_connected),
                                stringResource(R.string.share_internet_hint), tunnelUrl,
                            )
                            tunnelPreparing -> Text(stringResource(R.string.share_internet_preparing), color = c.muted)
                        }
                        // LAN (Wi-Fi/Ethernet) — snappier, but unencrypted HTTP.
                        if (lanUrl != null) {
                            ShareBlock(stringResource(R.string.share_lan_title), stringResource(R.string.share_lan_hint), lanUrl)
                        }
                        if (lanUrl == null && tunnelUrl == null && !tunnelPreparing) {
                            Text(stringResource(R.string.share_none))
                        }
                        Text(stringResource(R.string.share_warn), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
    )
}

@Composable
private fun ShareBlock(title: String, hint: String, url: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(hint, style = MaterialTheme.typography.bodySmall)
        Text(url, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        // Shareable link: copy it or send it through the system (SMS, messaging…).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostChip(stringResource(R.string.action_copy)) {
                clipboard.setText(AnnotatedString(url))
                Toast.makeText(context, context.getString(R.string.link_copied), Toast.LENGTH_SHORT).show()
            }
            GhostChip(stringResource(R.string.action_send)) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                }
                context.startActivity(Intent.createChooser(send, context.getString(R.string.share_via)))
            }
        }
        val qr = remember(url) { NetworkUtils.qrBitmap(url, 480).asImageBitmap() }
        Image(bitmap = qr, contentDescription = stringResource(R.string.qr_desc), modifier = Modifier.size(200.dp))
    }
}

@Composable
private fun ImportDialog(onImportUrl: (String) -> Unit, onImportText: (String) -> Unit, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val isUrl = input.trim().startsWith("http")
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { if (isUrl) onImportUrl(input) else onImportText(input) }, enabled = input.isNotBlank()) { Text(stringResource(R.string.action_import)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        title = { Text(stringResource(R.string.import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.import_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    label = { Text(stringResource(if (isUrl) R.string.import_label_url else R.string.import_label_any)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}
