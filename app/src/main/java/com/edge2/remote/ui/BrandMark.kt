package com.edge2.remote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.edge2.remote.ui.theme.Edge2

/**
 * Marque de l'app : miniature de la signature produit — pad sombre dans le
 * dégradé violet→rose, crosshair (axe base violet / axe tige rose) et point
 * lumineux à l'intersection. Même langage que l'icône de lancement.
 */
@Composable
fun BrandMark(size: Dp = 30.dp, modifier: Modifier = Modifier) {
    val c = Edge2.colors
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        // Tuile dégradée.
        drawRoundRect(
            brush = Brush.linearGradient(listOf(c.gradStart, c.gradEnd)),
            size = Size(s, s),
            cornerRadius = CornerRadius(s * 0.3f, s * 0.3f),
        )
        // Pad sombre.
        val pad = s * 0.18f
        drawRoundRect(
            color = Color(0xFF0C0C12),
            topLeft = Offset(pad, pad),
            size = Size(s - pad * 2, s - pad * 2),
            cornerRadius = CornerRadius(s * 0.16f, s * 0.16f),
        )
        // Crosshair + point (position décalée, comme un vrai pad).
        val cx = s * 0.6f
        val cy = s * 0.5f
        drawLine(c.base.copy(alpha = .9f), Offset(cx, pad + s * .06f), Offset(cx, s - pad - s * .06f), s * 0.04f, StrokeCap.Round)
        drawLine(c.tige.copy(alpha = .9f), Offset(pad + s * .06f, cy), Offset(s - pad - s * .06f, cy), s * 0.04f, StrokeCap.Round)
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = .5f), Color.Transparent), center = Offset(cx, cy), radius = s * 0.2f),
            radius = s * 0.2f, center = Offset(cx, cy),
        )
        drawCircle(Color.White, radius = s * 0.11f, center = Offset(cx, cy))
    }
}
