package com.edge2.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edge2.remote.ui.theme.Edge2

/**
 * XY pad — the design's signature element. One thumb drives both motors:
 * X axis (left→right) = motor 1, Y axis (bottom→top) = motor 2.
 *
 * "Pure" component: it draws the dot from [base]/[shaft] (0..1) and reports the
 * raw touched position through [onChange]. Link mode (average of both axes) is
 * handled by the caller, which then passes base = shaft.
 */
@Composable
fun XYPad(
    base: Float,
    shaft: Float,
    onChange: (base: Float, shaft: Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    xLabel: String = "MOTOR 1 →",
    yLabel: String = "MOTOR 2 →",
) {
    val c = Edge2.colors
    var size by remember { mutableStateOf(IntSize(1, 1)) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(c.pad)
            .border(1.dp, c.outline, RoundedCornerShape(24.dp))
            .onSizeChanged { size = it }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                fun emit(pos: Offset) {
                    val x = (pos.x / size.width).coerceIn(0f, 1f)
                    val y = (1f - pos.y / size.height).coerceIn(0f, 1f)
                    onChange(x, y)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    emit(down.position); down.consume()
                    drag(down.id) { ch -> emit(ch.position); ch.consume() }
                }
            },
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height

            // 4×4 grid.
            val gridColor = if (c.isDark) Color.White.copy(alpha = .035f) else c.ink.copy(alpha = .05f)
            for (i in 1..3) {
                val gx = w * i / 4f
                val gy = h * i / 4f
                drawLine(gridColor, Offset(gx, 0f), Offset(gx, h), 1f)
                drawLine(gridColor, Offset(0f, gy), Offset(w, gy), 1f)
            }

            // Gradient wash: motor 1 (violet) from the left, motor 2 (pink) from the bottom.
            drawRect(Brush.horizontalGradient(
                0f to c.base.copy(alpha = .10f), .55f to Color.Transparent,
                startX = 0f, endX = w,
            ))
            drawRect(Brush.verticalGradient(
                0f to Color.Transparent, .45f to Color.Transparent, 1f to c.shaft.copy(alpha = .10f),
                startY = 0f, endY = h,
            ))

            // Dot position.
            val px = (base.coerceIn(0f, 1f)) * w
            val py = (1f - shaft.coerceIn(0f, 1f)) * h

            // Following crosshairs.
            drawLine(c.base.copy(alpha = .45f), Offset(px, 0f), Offset(px, h), 1.5.dp.toPx())
            drawLine(c.shaft.copy(alpha = .45f), Offset(0f, py), Offset(w, py), 1.5.dp.toPx())

            // Halo + glowing dot.
            val r = 17.dp.toPx()
            drawCircle(
                Brush.radialGradient(
                    0f to c.base.copy(alpha = .55f), 1f to Color.Transparent,
                    center = Offset(px, py), radius = r * 2.6f,
                ),
                radius = r * 2.6f, center = Offset(px, py),
            )
            drawCircle(
                Brush.radialGradient(
                    0f to Color.White, 1f to (if (c.isDark) Color(0xFFC9B8FF) else Color(0xFFD7CCFF)),
                    center = Offset(px - r * .3f, py - r * .3f), radius = r,
                ),
                radius = r, center = Offset(px, py),
            )
            drawCircle(Color.White, radius = r, center = Offset(px, py), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }

        // Axis labels.
        Text(
            xLabel,
            color = c.base.copy(alpha = .75f),
            fontWeight = FontWeight.SemiBold,
            fontSize = 9.sp,
            letterSpacing = 2.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 9.dp),
        )
        Text(
            yLabel,
            color = c.shaft.copy(alpha = .75f),
            fontWeight = FontWeight.SemiBold,
            fontSize = 9.sp,
            letterSpacing = 2.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 2.dp, top = 36.dp)
                .graphicsLayer { rotationZ = -90f },
        )
    }
}
