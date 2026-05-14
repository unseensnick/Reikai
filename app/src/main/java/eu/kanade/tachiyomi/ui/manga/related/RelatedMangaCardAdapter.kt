package eu.kanade.tachiyomi.ui.manga.related

import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
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
     * the next manga-details screen. The full [RelatedMangaCardItem] is forwarded so the click
     * handler can branch on `sourceId` — tracker-origin items use a sentinel and route through
     * Global Search instead of trying to open the tracker URL as if it were a source URL.
     */
    interface OnRelatedMangaClickListener {
        fun onRelatedMangaClick(item: RelatedMangaCardItem)
    }
}
