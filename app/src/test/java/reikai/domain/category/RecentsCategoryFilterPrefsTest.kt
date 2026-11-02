package reikai.domain.category

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.recents.EmittingPreferenceStore

class RecentsCategoryFilterPrefsTest {

    private val preferences = ReikaiSourcePreferences(EmittingPreferenceStore())

    @ParameterizedTest
    @EnumSource(RecentsSurface::class)
    fun `each surface resolves its own three keys`(surface: RecentsSurface) {
        val (enabled, include, exclude) = preferences.categoryFilterPrefs(surface)
        val prefix = surface.name.lowercase()

        listOf(enabled.key(), include.key(), exclude.key()) shouldContainExactly listOf(
            "${prefix}_filter_categories_enabled",
            "${prefix}_filter_categories_include",
            "${prefix}_filter_categories_exclude",
        )
    }

    @Test
    fun `turning the combined tab on carries the filter Updates was using`() {
        val (enabled, include, exclude) = preferences.categoryFilterPrefs(RecentsSurface.UPDATES)
        enabled.set(true)
        include.set(setOf("7"))
        exclude.set(setOf("9"))
        preferences.updatesContentType.set(ContentType.NOVELS)

        preferences.seedRecentsSurfaceFromUpdates()

        val (toEnabled, toInclude, toExclude) = preferences.categoryFilterPrefs(RecentsSurface.RECENTS)
        listOf(toEnabled.get(), toInclude.get(), toExclude.get(), preferences.recentsContentType.get()) shouldBe
            listOf(true, setOf("7"), setOf("9"), ContentType.NOVELS)
    }

    /**
     * Only the first time. The seed runs on a switch the user can flip back and forth, and a second
     * pass would throw away whatever they had since chosen for the combined tab.
     */
    @Test
    fun `a combined selection already made is never overwritten`() {
        preferences.recentsContentType.set(ContentType.MANGA)
        val (enabled, include, _) = preferences.categoryFilterPrefs(RecentsSurface.RECENTS)
        enabled.set(true)
        include.set(setOf("3"))
        preferences.updatesContentType.set(ContentType.NOVELS)
        preferences.categoryFilterPrefs(RecentsSurface.UPDATES).second.set(setOf("7"))

        preferences.seedRecentsSurfaceFromUpdates()

        listOf(include.get(), preferences.recentsContentType.get()) shouldBe
            listOf(setOf("3"), ContentType.MANGA)
    }

    /** A write through Show is dropped, so a seed that wrote there never latched and ran on every flip. */
    @Test
    fun `the seed runs once under Show too`() {
        preferences.shownContent.set(ContentType.MANGA)
        val (_, include, _) = preferences.categoryFilterPrefs(RecentsSurface.RECENTS)
        preferences.categoryFilterPrefs(RecentsSurface.UPDATES).second.set(setOf("7"))
        preferences.seedRecentsSurfaceFromUpdates()
        include.set(setOf("3"))

        preferences.seedRecentsSurfaceFromUpdates()

        include.get() shouldBe setOf("3")
    }

    @Test
    fun `a seed under Show carries the chip the user chose, not the one Show forces`() {
        preferences.updatesContentType.set(ContentType.NOVELS)
        preferences.shownContent.set(ContentType.MANGA)
        preferences.seedRecentsSurfaceFromUpdates()

        preferences.shownContent.set(ContentType.ALL)

        preferences.recentsContentType.get() shouldBe ContentType.NOVELS
    }

    @Test
    fun `a selection stored for one surface leaves every other surface alone`() {
        val (enabled, include, exclude) = preferences.categoryFilterPrefs(RecentsSurface.UPDATES)
        enabled.set(true)
        include.set(setOf("7"))
        exclude.set(setOf("9"))

        RecentsSurface.entries.filterNot { it == RecentsSurface.UPDATES }.forEach { other ->
            val (otherEnabled, otherInclude, otherExclude) = preferences.categoryFilterPrefs(other)
            otherEnabled.get() shouldBe false
            otherInclude.get() shouldBe emptySet()
            otherExclude.get() shouldBe emptySet()
        }
    }
}
