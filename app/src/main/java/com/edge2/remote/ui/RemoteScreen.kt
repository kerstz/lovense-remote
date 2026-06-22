package com.edge2.remote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edge2.remote.R
import com.edge2.remote.RemoteViewModel
import com.edge2.remote.ble.ActuatorKind
import com.edge2.remote.ble.ConnectionState
import com.edge2.remote.ble.ToyRegistry
import com.edge2.remote.ble.ToyType
import com.edge2.remote.pattern.BuiltinPatterns
import com.edge2.remote.pattern.Pattern
import com.edge2.remote.remote.NetworkUtils
import com.edge2.remote.ui.theme.Edge2
import com.edge2.remote.ui.theme.JetBrainsMono
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Écran principal (toy connecté) — design `Edge2 Remote.dc.html`, **adaptatif**
 * selon les actionneurs du toy : pad XY pour les toys à 2 vibreurs (Edge), sinon
 * un slider étiqueté par actionneur (vibration / rotation / succion).
 */
@Composable
fun RemoteScreen(vm: RemoteViewModel, onDisconnect: () -> Unit) {
    val c = Edge2.colors
    val state by vm.connectionState.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val levels by vm.actuatorLevels.collectAsStateWithLifecycle()
    val shareUrl by vm.shareUrl.collectAsStateWithLifecycle()
    val tunnelUrl by vm.tunnelUrl.collectAsStateWithLifecycle()
    val tunnelConnected by vm.tunnelConnected.collectAsStateWithLifecycle()
    val imported by vm.importedPatterns.collectAsStateWithLifecycle()

    val connected = state as? ConnectionState.Connected
    val toy = connected?.toy ?: ToyRegistry.generic
    val battery = connected?.battery
    var shareOpen by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        // --- En-tête : appareil + état + batterie + actions partage --------
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(toy.displayName, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(c.live))
                    Text(stringResource(R.string.status_connected), color = c.live, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BatteryGlyph(battery)
                    Text(battery?.let { stringResource(R.string.battery_fmt, it) } ?: "BLE", color = c.muted, fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 11.sp)
                }
                Spacer(Modifier.size(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GhostChip(stringResource(R.string.action_share)) { vm.startSharing(); shareOpen = true }
                    GhostChip(stringResource(R.string.action_disconnect)) { onDisconnect() }
                }
            }
        }

        // --- Contrôle adapté au toy ----------------------------------------
        if (toy.isDualVibrate) {
            DualVibrateControls(vm, playing != null, levels)
        } else {
            ActuatorControls(vm, toy, playing != null, levels)
        }

        // --- Patterns pré-enregistrés (pilotent les actionneurs 0/1) -------
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (BuiltinPatterns.all + imported).forEachIndexed { i, p ->
                PatternChip(p.name, i, playing == p.name, Modifier.weight(1f)) {
                    if (playing == p.name) vm.stopAll() else vm.playPattern(p)
                }
            }
            PatternChip("+ Lovense", -1, false, Modifier.weight(1f)) { importOpen = true }
        }
    }

    if (shareOpen) {
        ShareDialog(shareUrl, tunnelUrl, tunnelConnected) { vm.stopSharing(); shareOpen = false }
    }
    if (importOpen) {
        ImportDialog(
            onImportUrl = { vm.importFromUrl(it); importOpen = false },
            onImportText = { vm.importFromText(it); importOpen = false },
            onDismiss = { importOpen = false },
        )
    }
}

/** Toys à 2 vibreurs (Edge, Gemini, Hyphy) : pad XY + readouts + Link + presets. */
@Composable
private fun DualVibrateControls(vm: RemoteViewModel, playing: Boolean, levels: List<Int>) {
    val c = Edge2.colors
    val link by vm.linkMode.collectAsStateWithLifecycle()
    // Drag manuel = vérité ; pendant un pattern, on suit les moteurs réels.
    var localBase by remember { mutableFloatStateOf(0f) }
    var localTige by remember { mutableFloatStateOf(0f) }
    val baseF = if (playing) levels.getOrElse(0) { 0 } / 20f else localBase
    val tigeF = if (playing) levels.getOrElse(1) { 0 } / 20f else localTige

    fun applyXY(x: Float, y: Float) {
        if (link) { val m = (x + y) / 2f; localBase = m; localTige = m; vm.setXY(m, m) }
        else { localBase = x; localTige = y; vm.setXY(x, y) }
    }
    fun preset(f: Float) { localBase = f; localTige = f; vm.setBoth(f) }

    Text(stringResource(R.string.hint_xy), color = c.muted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth())
    XYPad(base = baseF, tige = tigeF, onChange = ::applyXY, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Readout(stringResource(R.string.label_base), c.base, (baseF * 100).roundToInt())
        Readout(stringResource(R.string.label_tige), c.tige, (tigeF * 100).roundToInt())
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        LinkToggle(link) { vm.toggleLink() }
        PresetButton(stringResource(R.string.preset_soft), Modifier.weight(1f)) { preset(0.30f) }
        PresetButton(stringResource(R.string.preset_medium), Modifier.weight(1f)) { preset(0.60f) }
        PresetButton(stringResource(R.string.preset_strong), Modifier.weight(1f)) { preset(0.90f) }
    }
}

/** Autres toys : un slider étiqueté par actionneur (+ bouton sens pour la rotation). */
@Composable
private fun ActuatorControls(vm: RemoteViewModel, toy: ToyType, playing: Boolean, levels: List<Int>) {
    val c = Edge2.colors
    val local = remember(toy) { mutableStateListOf<Float>().apply { repeat(toy.actuators.size) { add(0f) } } }

    Text(stringResource(R.string.hint_actuators, toy.displayName), color = c.muted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth())
    toy.actuators.forEachIndexed { i, act ->
        val accent = when (act.kind) {
            ActuatorKind.VIBRATE, ActuatorKind.VIBRATE1 -> c.base
            ActuatorKind.VIBRATE2, ActuatorKind.ROTATE -> c.tige
            ActuatorKind.AIR -> c.live
        }
        val value = if (playing && i < levels.size) levels[i] / act.max.toFloat() else local.getOrElse(i) { 0f }
        ActuatorSlider(
            label = kindLabel(act.kind), accent = accent, fraction = value, percent = (value * 100).roundToInt(),
            reversible = act.reversible, onReverse = { vm.reverse(i) },
            onChange = { f -> if (i < local.size) local[i] = f; vm.setActuator(i, f) },
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        fun all(f: Float) { for (i in local.indices) local[i] = f; vm.setBoth(f) }
        PresetButton(stringResource(R.string.preset_soft), Modifier.weight(1f)) { all(0.30f) }
        PresetButton(stringResource(R.string.preset_medium), Modifier.weight(1f)) { all(0.60f) }
        PresetButton(stringResource(R.string.preset_strong), Modifier.weight(1f)) { all(0.90f) }
    }
}

/** Libellé localisé d'un type d'actionneur. */
@Composable
private fun kindLabel(kind: ActuatorKind): String = stringResource(
    when (kind) {
        ActuatorKind.VIBRATE, ActuatorKind.VIBRATE1, ActuatorKind.VIBRATE2 -> R.string.kind_vibrate
        ActuatorKind.ROTATE -> R.string.kind_rotate
        ActuatorKind.AIR -> R.string.kind_air
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

/** Petite onde : sinus / carré / rampe selon l'index (purement décoratif). */
private fun DrawScope.drawWave(index: Int, color: Color) {
    val w = size.width; val h = size.height; val mid = h / 2f
    val path = androidx.compose.ui.graphics.Path()
    val steps = 28
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val x = t * w
        val y = when (index) {
            1 -> mid - (if ((t * 4).toInt() % 2 == 0) h * .38f else -h * .38f) // carré (Pulse)
            2 -> h - t * h                                                      // rampe (Montée)
            else -> mid - sin(t * 2 * Math.PI * 2).toFloat() * h * .38f         // sinus
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

// --- Dialogs (Phase 4B + import) — héritent du thème Edge2 -----------------

@Composable
private fun ShareDialog(lanUrl: String?, tunnelUrl: String?, tunnelConnected: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.share_stop)) } },
        title = { Text(stringResource(R.string.share_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (tunnelUrl != null) {
                    val title = if (tunnelConnected) stringResource(R.string.share_internet_connected) else stringResource(R.string.share_internet_connecting)
                    ShareBlock(title, stringResource(R.string.share_internet_hint), tunnelUrl)
                }
                when {
                    lanUrl != null -> ShareBlock(stringResource(R.string.share_lan_title), stringResource(R.string.share_lan_hint), lanUrl)
                    tunnelUrl == null -> Text(stringResource(R.string.share_none))
                }
                Text(stringResource(R.string.share_warn), style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

@Composable
private fun ShareBlock(title: String, hint: String, url: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(hint, style = MaterialTheme.typography.bodySmall)
        Text(url, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
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
