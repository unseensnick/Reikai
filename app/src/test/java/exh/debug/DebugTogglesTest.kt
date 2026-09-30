package exh.debug

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference

/**
 * The debug toggles keep Komikku's names, keys and defaults, so a value a user set there survives, and
 * an unset one behaves as Komikku ships it.
 */
class DebugTogglesTest {

    @ParameterizedTest
    @CsvSource(
        "ENABLE_EXH_ROOT_REDIRECT, true",
        "ENABLE_DEBUG_OVERLAY, false",
        "PULL_TO_ROOT_WHEN_LOADING_EXH_MANGA_DETAILS, true",
        "RESTRICT_EXH_GALLERY_UPDATE_CHECK_FREQUENCY, true",
        "INCLUDE_ONLY_ROOT_WHEN_LOADING_EXH_VERSIONS, false",
        "HIDE_COVER_IMAGE_ONLY_SHOW_COLOR, false",
    )
    fun `an unset toggle takes Komikku's default`(name: String, default: Boolean) {
        DebugToggles.valueOf(name).preference(InMemoryPreferenceStore()).get() shouldBe default
    }

    @ParameterizedTest
    @CsvSource(
        "ENABLE_EXH_ROOT_REDIRECT, eh_debug_toggle_enable_exh_root_redirect",
        "ENABLE_DEBUG_OVERLAY, eh_debug_toggle_enable_debug_overlay",
        "PULL_TO_ROOT_WHEN_LOADING_EXH_MANGA_DETAILS, eh_debug_toggle_pull_to_root_when_loading_exh_manga_details",
        "RESTRICT_EXH_GALLERY_UPDATE_CHECK_FREQUENCY, eh_debug_toggle_restrict_exh_gallery_update_check_frequency",
        "INCLUDE_ONLY_ROOT_WHEN_LOADING_EXH_VERSIONS, eh_debug_toggle_include_only_root_when_loading_exh_versions",
        "HIDE_COVER_IMAGE_ONLY_SHOW_COLOR, eh_debug_toggle_hide_cover_image_only_show_color",
    )
    fun `a toggle reads the value stored under Komikku's key`(name: String, key: String) {
        val toggle = DebugToggles.valueOf(name)
        val store = InMemoryPreferenceStore(sequenceOf(InMemoryPreference(key, !toggle.default, toggle.default)))

        toggle.preference(store).get() shouldBe !toggle.default
    }
}
