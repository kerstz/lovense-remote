package com.edge2.remote.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.edge2.remote.R

/** Poids d'une police variable (TTF `[wght]`) — supporté API 26+. */
@OptIn(ExperimentalTextApi::class)
private fun variableFont(resId: Int, weight: FontWeight, axis: Int) =
    Font(resId, weight = weight, variationSettings = FontVariation.Settings(FontVariation.weight(axis)))

/** Space Grotesk — display + corps. Caractère géométrique, signature du design. */
val SpaceGrotesk = FontFamily(
    variableFont(R.font.space_grotesk, FontWeight.Normal, 400),
    variableFont(R.font.space_grotesk, FontWeight.Medium, 500),
    variableFont(R.font.space_grotesk, FontWeight.SemiBold, 600),
    variableFont(R.font.space_grotesk, FontWeight.Bold, 700),
)

/** JetBrains Mono — données chiffrées (batterie, %, latence, ID appareil). */
val JetBrainsMono = FontFamily(
    variableFont(R.font.jetbrains_mono, FontWeight.Medium, 500),
    variableFont(R.font.jetbrains_mono, FontWeight.Bold, 700),
)

private fun Typography.withFamily(f: FontFamily) = copy(
    displayLarge = displayLarge.copy(fontFamily = f),
    displayMedium = displayMedium.copy(fontFamily = f),
    displaySmall = displaySmall.copy(fontFamily = f),
    headlineLarge = headlineLarge.copy(fontFamily = f),
    headlineMedium = headlineMedium.copy(fontFamily = f),
    headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f),
    titleMedium = titleMedium.copy(fontFamily = f),
    titleSmall = titleSmall.copy(fontFamily = f),
    bodyLarge = bodyLarge.copy(fontFamily = f),
    bodyMedium = bodyMedium.copy(fontFamily = f),
    bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f),
    labelMedium = labelMedium.copy(fontFamily = f),
    labelSmall = labelSmall.copy(fontFamily = f),
)

val Edge2Typography: Typography = Typography().withFamily(SpaceGrotesk)

/** Style mono prêt à l'emploi pour les valeurs chiffrées. */
val MonoNumber = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold)
