package com.edge2.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Gros slider VERTICAL pensé pour le pouce : on touche/glisse n'importe où sur
 * la barre, la valeur suit la hauteur (bas = 0, haut = 1). Remplissage depuis
 * le bas + valeur 0..20 affichée.
 *
 * Slider Material étant horizontal, on le code à la main (Canvas + gestes).
 */
@Composable
fun VerticalSlider(
    fraction: Float,
    onFraction: (Float) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fillColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    var heightPx by remember { mutableFloatStateOf(1f) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(trackColor)
            .onSizeChanged { heightPx = it.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                // bas de la barre = 0, haut = 1
                fun emit(y: Float) = onFraction((1f - y / heightPx).coerceIn(0f, 1f))
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    emit(down.position.y)
                    down.consume()
                    drag(down.id) { change ->
                        emit(change.position.y)
                        change.consume()
                    }
                }
            },
    ) {
        // Remplissage depuis le bas.
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val fillH = size.height * fraction
            val r = CornerRadius(size.width / 2f, size.width / 2f)
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(0f, size.height - fillH),
                size = Size(size.width, fillH),
                cornerRadius = r,
            )
        }
        // Valeur 0..20 en bas.
        Text(
            text = "${(fraction * 20).roundToInt()}",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(bottom = 18.dp),
        )
        // Libellé en haut.
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(top = 18.dp),
        )
    }
}
