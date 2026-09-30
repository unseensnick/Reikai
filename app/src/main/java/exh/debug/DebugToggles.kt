package exh.debug

import android.content.Context
import eu.kanade.core.preference.PreferenceMutableState
import kotlinx.coroutines.CoroutineScope
import mihon.app.di.appGraph
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

/**
 * Komikku's debug toggles, switched from Settings, Advanced, Open debug menu. The names and the
 * `eh_debug_toggle_<name>` keys are Komikku's, so a value set there carries over.
 */
enum class DebugToggles(val default: Boolean) {
    // Redirect to master version of gallery when encountering a gallery that has a parent/child that is already in the library
    ENABLE_EXH_ROOT_REDIRECT(true),

    // Enable debug overlay (only available in debug builds)
    ENABLE_DEBUG_OVERLAY(false),

    // Convert non-root galleries into root galleries when loading them
    PULL_TO_ROOT_WHEN_LOADING_EXH_MANGA_DETAILS(true),

    // Do not update the same gallery too often
    RESTRICT_EXH_GALLERY_UPDATE_CHECK_FREQUENCY(true),

    // Pretend that all galleries only have a single version
    INCLUDE_ONLY_ROOT_WHEN_LOADING_EXH_VERSIONS(false),

    // Draw every cover as a plain placeholder instead of its image
    HIDE_COVER_IMAGE_ONLY_SHOW_COLOR(false),
    ;

    private val prefKey = "eh_debug_toggle_${name.lowercase(Locale.US)}"

    fun preference(store: PreferenceStore): Preference<Boolean> = store.getBoolean(prefKey, default)

    var enabled: Boolean
        get() = preference(preferenceStore).get()
        set(value) {
            preference(preferenceStore).set(value)
        }

    fun asPref(scope: CoroutineScope) = PreferenceMutableState(preference(preferenceStore), scope)

    companion object {
        private val preferenceStore: PreferenceStore
            get() = Injekt.get<Context>().appGraph.preferenceStore
    }
}
