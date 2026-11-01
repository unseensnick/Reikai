package reikai.presentation.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.sample
import tachiyomi.core.common.preference.Preference
import kotlin.time.Duration.Companion.seconds

/**
 * How the page is lit and tinted: custom brightness, a colour filter over the page, grayscale and
 * inverted colours. Both readers support all of it, and each keeps its own values, so the host applies
 * whichever set the open session hands over and the settings sheet edits that same set.
 */
class ReaderDisplayFilters(
    val customBrightness: Preference<Boolean>,
    /** -75 to 100: below 0 dims with an overlay, above 0 sets the screen brightness, 0 is the system's. */
    val customBrightnessValue: Preference<Int>,
    val colorFilter: Preference<Boolean>,
    /** Packed ARGB. */
    val colorFilterValue: Preference<Int>,
    /** An index into `ReaderPreferences.ColorFilterMode`. */
    val colorFilterMode: Preference<Int>,
    val grayscale: Preference<Boolean>,
    val invertedColors: Preference<Boolean>,
)

/**
 * The custom brightness, 0 while it is off. A collector that outlived its toggle was how a switched-off brightness
 * kept moving, so the value is only followed while the setting is on (mihon 8124d324a).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
fun ReaderDisplayFilters.brightness(): Flow<Int> = customBrightness.changes()
    .flatMapLatest { enabled -> if (enabled) customBrightnessValue.changes().sample(0.1.seconds) else flowOf(0) }
