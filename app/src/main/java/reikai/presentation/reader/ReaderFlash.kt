package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import reikai.domain.novel.NovelPreferences
import tachiyomi.core.common.preference.Preference

/**
 * The flash after a page turn that clears ghosting on an e-ink screen. Each content type keeps its own
 * values, as with [ReaderDisplayFilters], so the host flashes by whichever set the open session hands over.
 */
class ReaderFlash(
    val onPageChange: Preference<Boolean>,
    val durationMillis: Preference<Int>,
    /** Flash on every this many turns. */
    val pageInterval: Preference<Int>,
    val color: Preference<ReaderPreferences.FlashColor>,
) {
    companion object {
        fun manga(preferences: ReaderPreferences) = ReaderFlash(
            onPageChange = preferences.flashOnPageChange,
            durationMillis = preferences.flashDurationMillis,
            pageInterval = preferences.flashPageInterval,
            color = preferences.flashColor,
        )

        fun novel(preferences: NovelPreferences) = ReaderFlash(
            onPageChange = preferences.readerFlashOnPageChange(),
            durationMillis = preferences.readerFlashDurationMillis(),
            pageInterval = preferences.readerFlashPageInterval(),
            color = preferences.readerFlashColor(),
        )
    }
}
