package yokai.domain.ui.settings

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.core.preference.PreferenceStore
import eu.kanade.tachiyomi.core.preference.getEnum
import eu.kanade.tachiyomi.data.preference.PreferenceKeys
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerConfig
import yokai.i18n.MR

class ReaderPreferences(private val preferenceStore: PreferenceStore) {
    fun cutoutShort() = preferenceStore.getBoolean("cutout_short", true)

    fun pagerCutoutBehavior() = preferenceStore.getEnum(PreferenceKeys.pagerCutoutBehavior, CutoutBehaviour.IGNORE)

    fun landscapeCutoutBehavior() = preferenceStore.getEnum("landscape_cutout_behavior", LandscapeCutoutBehaviour.HIDE)

    enum class CutoutBehaviour(val titleResId: StringResource) {
        HIDE(MR.strings.pad_cutout_areas),  // Similar to CUTOUT_MODE_NEVER / J2K's pad
        SHOW(MR.strings.start_past_cutout), // Similar to CUTOUT_MODE_SHORT_EDGES / J2K's start past
        IGNORE(MR.strings.cutout_ignore),   // Similar to CUTOUT_MODE_DEFAULT / J2K's ignore
        ;

        companion object {
            fun migrate(oldValue: Int) =
                when (oldValue) {
                    PagerConfig.CUTOUT_PAD -> CutoutBehaviour.HIDE
                    PagerConfig.CUTOUT_IGNORE -> CutoutBehaviour.IGNORE
                    else -> CutoutBehaviour.SHOW
                }
        }
    }

    enum class LandscapeCutoutBehaviour(val titleResId: StringResource) {
        HIDE(MR.strings.pad_cutout_areas),  // Similar to CUTOUT_MODE_NEVER / J2K's pad
        DEFAULT(MR.strings.cutout_ignore),  // Similar to CUTOUT_MODE_SHORT_EDGES / J2K's ignore
        ;

        companion object {
            fun migrate(oldValue: Int) =
                when (oldValue) {
                    0 -> LandscapeCutoutBehaviour.HIDE
                    else -> LandscapeCutoutBehaviour.DEFAULT
                }
        }
    }

    fun markReadOnSkip() = preferenceStore.getBoolean("pref_mark_read_on_skip", false)

    fun webtoonDoubleTapZoomEnabled() = preferenceStore.getBoolean("pref_enable_double_tap_zoom_webtoon", true)

    fun debugMode() = preferenceStore.getBoolean("pref_enable_reader_debug_mode", BuildConfig.DEBUG)
}
