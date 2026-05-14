package eu.kanade.tachiyomi.ui.manga.related

import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.model.SManga
import uy.kohesive.injekt.injectLazy
import yokai.domain.ui.UiPreferences

/**
 * Adapter for the related-mangas carousel rendered below the description on the manga details
 * screen. Mirrors [GlobalSearchCardAdapter]'s shape but works with raw [SManga] entries (source
 * suggestions are not in the library yet, so they don't have a [Manga] row to flow off of).
 */
class RelatedMangaCardAdapter(listener: OnRelatedMangaClickListener) :
    FlexibleAdapter<RelatedMangaCardItem>(null, listener, true) {

    val mangaClickListener: OnRelatedMangaClickListener = listener
    private val uiPreferences: UiPreferences by injectLazy()
    private val preferences: PreferencesHelper by injectLazy()
    val showOutlines = uiPreferences.outlineOnCovers().get()

    /**
     * Click contract for the carousel. The owning controller implements this to route taps to
     * the next manga-details screen.
     */
    interface OnRelatedMangaClickListener {
        fun onRelatedMangaClick(manga: SManga)
    }
}
