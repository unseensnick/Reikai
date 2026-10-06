package reikai.presentation.components

/**
 * The pulse a loading placeholder runs, fading between [MIN] and [MAX] strength and back every
 * [HALF_PERIOD_MS] each way: the details skeleton, the text renderer's picture box and the WebView
 * page's `rk-pulse` in reader.css, which ImageLoadingPulseTest holds to these values.
 */
internal object LoadingPulse {
    const val MIN = 0.45f
    const val MAX = 0.9f
    const val HALF_PERIOD_MS = 900
}
