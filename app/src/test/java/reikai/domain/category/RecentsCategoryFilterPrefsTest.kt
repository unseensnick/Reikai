package reikai.domain.category

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.source.ReikaiSourcePreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class RecentsCategoryFilterPrefsTest {

    private val preferences = ReikaiSourcePreferences(InMemoryPreferenceStore())

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
