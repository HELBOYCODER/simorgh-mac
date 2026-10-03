package com.simorgh.mac.compat

import androidx.compose.runtime.Composable

/** Desktop replacement for the Android lifecycle's repeatOnLifecycle: runs the block inline. */
suspend inline fun Any?.keepOn(block: suspend () -> Unit) {
    block()
}

/** No-op on desktop: there is no system back gesture. */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    // Intentionally empty: the window's close handler owns exit.
}

class BackEventCompat(val progress: Float)

/** No-op on desktop: sheets do not track a predictive back gesture. */
@Composable
fun PredictiveBackHandler(enabled: Boolean = true, onBack: suspend (kotlinx.coroutines.flow.Flow<BackEventCompat>) -> Unit) {
    // Intentionally empty.
}
