package reikai.domain.track.site

import eu.kanade.tachiyomi.data.track.TrackerManager
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class OwnedSitesTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://www.novelupdates.com",
            "https://novelupdates.com/",
            "https://WWW.NovelUpdates.com/series/x/",
        ],
    )
    fun `NovelUpdates' pages are its tracker's, however the address is written`(site: String) {
        OwnedSites.ownerOf(site)?.trackerId shouldBe TrackerManager.NOVELUPDATES
    }

    @Test
    fun `another site has no owner`() {
        OwnedSites.ownerOf("https://novelnice.com").shouldBeNull()
    }

    @Test
    fun `an address that does not parse has no owner`() {
        OwnedSites.ownerOf("not a url").shouldBeNull()
    }

    @Test
    fun `NovelUpdates hides the settings its tracker took over`() {
        OwnedSites.ownerOf("https://www.novelupdates.com")!!.hiddenSourceKeys shouldBe setOf(
            "pref_enable_tracking",
            "pref_track_last_read",
            "pref_track_notes",
            "pref_track_unread",
            "pref_protect_highest",
            "pref_reset_cache_toggle",
        )
    }
}
