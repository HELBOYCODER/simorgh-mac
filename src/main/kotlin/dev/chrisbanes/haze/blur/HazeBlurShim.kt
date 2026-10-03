package dev.chrisbanes.haze.blur

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

class HazeBlurStyle private constructor() {
    class Builder {
        fun blurRadius(v: Any?) = Unit
        fun noiseFactor(v: Float) = Unit
        fun backgroundColor(v: Color) = Unit
        fun colorEffects(v: List<HazeColorEffect>) = Unit
    }
    companion object {
        operator fun invoke(block: Builder.() -> Unit): HazeBlurStyle {
            Builder().block()
            return HazeBlurStyle()
        }
    }
}

class HazeColorEffect private constructor() {
    companion object {
        fun tint(color: Color): HazeColorEffect = HazeColorEffect()
    }
}

fun Modifier.hazeBlur(input: Any?, style: HazeBlurStyle? = null, performanceMode: Any? = null): Modifier = this
