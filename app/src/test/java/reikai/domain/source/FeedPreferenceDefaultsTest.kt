package reikai.domain.source

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/**
 * The Feed ships off, on every switch. The implementations this was ported from ship it on, including
 * making a source's feed that source's front door, so installing an update would rearrange Browse for
 * everyone who did not ask for it. Flipping any of these is an owner ruling, not a tidy-up.
 */
class FeedPreferenceDefaultsTest {

    private val preferences = ReikaiSourcePreferences(InMemoryPreferenceStore())

    @Test
    fun `the Feed tab is hidden until it is asked for`() {
        preferences.showFeedTab.get() shouldBe false
    }

    @Test
    fun `Browse does not open on the Feed tab`() {
        preferences.feedTabInFront.get() shouldBe false
    }

    @Test
    fun `feed rows show entries already in the library`() {
        preferences.hideInLibraryFeedItems.get() shouldBe false
    }
}
