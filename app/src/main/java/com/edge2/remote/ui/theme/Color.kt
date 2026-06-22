package com.edge2.remote.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Palette identité Edge2 (issue du design `Edge2 Remote.dc.html`).
 *
 * Signature = dégradé violet→rose [gradStart]→[gradEnd]. Chaque moteur a son
 * accent : [base] violet, [tige] rose. [live] = vert « connecté ». On ne passe
 * PAS par les couleurs dynamiques Material : l'identité de marque est fixe.
 */
data class Edge2Colors(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val pad: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val base: Color,
    val tige: Color,
    val live: Color,
    val gradStart: Color,
    val gradEnd: Color,
    val outline: Color,
    val danger: Color,
)

val Edge2Dark = Edge2Colors(
    isDark = true,
    bg = Color(0xFF0C0C12),
    surface = Color(0xFF14141C),
    surfaceElevated = Color(0xFF181722),
    pad = Color(0xFF0F0F17),
    ink = Color(0xFFFFFFFF),
    muted = Color(0xFF8A8A97),
    faint = Color(0xFF6E6E7A),
    base = Color(0xFFA98BFF),
    tige = Color(0xFFFF8BA1),
    live = Color(0xFF46E0A0),
    gradStart = Color(0xFF8B6BFF),
    gradEnd = Color(0xFFFF5C7A),
    outline = Color(0x14FFFFFF),
    danger = Color(0xFFFF5C7A),
)

val Edge2Light = Edge2Colors(
    isDark = false,
    bg = Color(0xFFF4F3F8),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFFFFFFF),
    pad = Color(0xFFFFFFFF),
    ink = Color(0xFF16161D),
    muted = Color(0xFF76747E),
    faint = Color(0xFFA6A4B0),
    base = Color(0xFF6D4DF2),
    tige = Color(0xFFF0436A),
    live = Color(0xFF1FB877),
    gradStart = Color(0xFF8B6BFF),
    gradEnd = Color(0xFFFF5C7A),
    outline = Color(0x1416161D),
    danger = Color(0xFFF0436A),
)

val LocalEdge2Colors = staticCompositionLocalOf { Edge2Dark }
