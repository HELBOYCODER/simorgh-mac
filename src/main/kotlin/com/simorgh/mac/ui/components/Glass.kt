package com.simorgh.mac.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.simorgh.mac.ui.theme.LocalGlassEnabled
import com.simorgh.mac.ui.theme.ZeroTheme
import dev.chrisbanes.haze.HazeState

/** The page-level haze source that the bottom bar and sheets sample. */
val LocalRootHaze = staticCompositionLocalOf<HazeState?> { null }

/**
 * Glass is used on exactly three surfaces: the floating bottom bar, bottom
 * sheets, and a top bar once content scrolls under it. Everything else is
 * opaque so text keeps AA contrast.
 *
 * Real blur needs RenderEffect, which Compose Desktop does not expose here,
 * so the surface is the tinted, nearly opaque fallback — the same rendering
 * the Android app uses below API 31 and in battery saver.
 */
@Composable
fun Modifier.glass(state: HazeState?, shape: Shape, edge: Boolean = true): Modifier {
    val colors = ZeroTheme.colors
    val edgeBrush = remember(colors) {
        Brush.verticalGradient(0f to colors.glassEdge, 0.5f to colors.glassEdge.copy(alpha = colors.glassEdge.alpha * 0.25f), 1f to androidx.compose.ui.graphics.Color.Transparent)
    }
    val m = this.clip(shape).background(colors.glassFallback)
    return if (edge) m.border(1.dp, edgeBrush, shape) else m
}

/** Blur availability: always the opaque fallback on desktop. */
@Composable
fun rememberGlassAllowed(): Boolean = false
