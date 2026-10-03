// Desktop stand-ins for the haze blur API: Compose Desktop has no RenderEffect
// blur, so glass surfaces render as the theme's opaque fallback instead.
package dev.chrisbanes.haze

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

class HazeState

class HazeInput private constructor() {
    companion object {
        fun Sources(state: HazeState): HazeInput = HazeInput()
    }
}

enum class HazePerformanceMode { Normal, Adaptive }

@Composable
fun rememberHazeState(): HazeState = remember { HazeState() }

fun Modifier.hazeSource(state: HazeState, alpha: Float = 1f): Modifier = this

fun Modifier.hazeEffect(input: HazeInput, style: Any? = null): Modifier = this
