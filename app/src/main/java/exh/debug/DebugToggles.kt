package exh.debug

import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.injectLazy
import java.util.Locale

/**
 * Phase 2b subset of TachiyomiSY's debug toggles: only the two flags the E-Hentai source reads
 * to decide whether to pull a gallery to its newest version and whether to expose every version
 * as a chapter. The full toggle set (debug overlay, update-frequency limits) lands with later
 * EXH phases.
 */
enum class DebugToggles(val default: Boolean) {
    // Convert non-root galleries into root galleries when loading them
    PULL_TO_ROOT_WHEN_LOADING_EXH_MANGA_DETAILS(true),

    // Pretend that all galleries only have a single version
    INCLUDE_ONLY_ROOT_WHEN_LOADING_EXH_VERSIONS(false),
    ;

    private val prefKey = "eh_debug_toggle_${name.lowercase(Locale.US)}"

    val enabled: Boolean
        get() = preferenceStore.getBoolean(prefKey, default).get()

    companion object {
        private val preferenceStore: PreferenceStore by injectLazy()
    }
}
