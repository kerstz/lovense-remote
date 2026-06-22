package com.edge2.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edge2.remote.R
import com.edge2.remote.ui.theme.Edge2

/** Réglages : langue et thème. Toute sélection applique + recrée l'écran. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsDialog(
    lang: String,
    theme: String,
    onLang: (String) -> Unit,
    onTheme: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Edge2.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        title = { Text(stringResource(R.string.settings_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.settings_language), color = c.ink, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptChip(stringResource(R.string.opt_system), lang == "system") { onLang("system") }
                    OptChip("Français", lang == "fr") { onLang("fr") }
                    OptChip("English", lang == "en") { onLang("en") }
                    OptChip("Español", lang == "es") { onLang("es") }
                }
                Text(stringResource(R.string.settings_theme), color = c.ink, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptChip(stringResource(R.string.opt_system), theme == "system") { onTheme("system") }
                    OptChip(stringResource(R.string.opt_dark), theme == "dark") { onTheme("dark") }
                    OptChip(stringResource(R.string.opt_light), theme == "light") { onTheme("light") }
                }
            }
        },
    )
}

@Composable
private fun OptChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Edge2.colors
    Text(
        label,
        color = if (selected) c.ink else c.muted,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) c.gradStart.copy(alpha = .16f) else c.surface.copy(alpha = if (c.isDark) .35f else 1f))
            .border(1.dp, if (selected) c.gradStart else c.outline, RoundedCornerShape(11.dp))
            .clickable { onClick() }
            .padding(horizontal = 13.dp, vertical = 8.dp),
    )
}
