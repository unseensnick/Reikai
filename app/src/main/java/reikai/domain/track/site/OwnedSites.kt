package reikai.domain.track.site

import eu.kanade.tachiyomi.data.track.TrackerManager
import reikai.domain.source.siteHost
import reikai.novel.source.NovelSource

/**
 * A site whose tracking a Reikai tracker has taken over from the extensions reading that site: the
 * extension's own tracking hooks never run, and its settings for them are hidden, keyed by [hiddenSourceKeys].
 */
data class OwnedSite(val trackerId: Long, val host: String, val hiddenSourceKeys: Set<String>)

object OwnedSites {

    /**
     * NovelUpdates' extension writes the same site as Reikai's tracker, destructively: its notes sync
     * truncates a note at its first quote. Record: docs/dev/plans/content-layer-sources-surface.md.
     */
    private val all = listOf(
        OwnedSite(
            trackerId = TrackerManager.NOVELUPDATES,
            host = "novelupdates.com",
            hiddenSourceKeys = setOf(
                "pref_enable_tracking",
                "pref_track_last_read",
                "pref_track_notes",
                "pref_track_unread",
                "pref_protect_highest",
                "pref_reset_cache_toggle",
            ),
        ),
    )

    /** The site [siteUrl] is on, when a Reikai tracker owns it; matched by host, with or without `www.`. */
    fun ownerOf(siteUrl: String): OwnedSite? {
        val host = siteHost(siteUrl) ?: return null
        return all.firstOrNull { it.host == host }
    }

    fun ownerOf(source: NovelSource): OwnedSite? = ownerOf(source.site)
}
