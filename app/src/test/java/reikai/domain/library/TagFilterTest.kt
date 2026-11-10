package reikai.domain.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.presentation.library.normalizeDynamicKey
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.TriState

class TagFilterTest {

    @Test
    fun `an inactive filter keeps every series`() {
        TagFilter().matches(listOf("Action")) shouldBe true
    }

    @Test
    fun `a shown tag keeps a series carrying it in another spelling`() {
        TagFilter(include = setOf("slice of life")).matches(listOf("Slice_of-Life")) shouldBe true
    }

    @Test
    fun `a shown tag drops a series without it`() {
        TagFilter(include = setOf("romance")).matches(listOf("Action")) shouldBe false
    }

    @Test
    fun `shown tags need only one of them by default`() {
        TagFilter(include = setOf("action", "romance")).matches(listOf("Romance")) shouldBe true
    }

    @Test
    fun `shown tags with Require all drop a series missing one`() {
        TagFilter(include = setOf("action", "romance"), includeAll = true).matches(listOf("Romance")) shouldBe false
    }

    @Test
    fun `a hidden tag drops a series carrying it`() {
        TagFilter(exclude = setOf("horror")).matches(listOf("Action", "Horror")) shouldBe false
    }

    @Test
    fun `hidden tags with Hide all keep a series carrying only one`() {
        TagFilter(exclude = setOf("horror", "gore"), excludeAll = true).matches(listOf("Horror")) shouldBe true
    }

    @Test
    fun `hidden tags with Hide all drop a series carrying every one`() {
        TagFilter(exclude = setOf("horror", "gore"), excludeAll = true).matches(listOf("Gore", "Horror")) shouldBe false
    }

    @Test
    fun `a hidden tag wins over a shown one`() {
        TagFilter(include = setOf("action"), exclude = setOf("horror"))
            .matches(listOf("Action", "Horror")) shouldBe false
    }

    @Test
    fun `No tags shown keeps a series whose tags are all blank`() {
        TagFilter(noTags = TriState.ENABLED_IS).matches(listOf(" ", "")) shouldBe true
    }

    @Test
    fun `No tags hidden drops a series without tags`() {
        TagFilter(noTags = TriState.ENABLED_NOT).matches(emptyList()) shouldBe false
    }

    @Test
    fun `choices count each series once per tag, most used first, with the first spelling seen`() {
        tagChoices(listOf(listOf("Action", "action"), listOf("ACTION", "Drama"))) shouldBe listOf(
            TagChoice("action", "Action", 2),
            TagChoice("drama", "Drama", 1),
        )
    }

    @Test
    fun `a source's ALL CAPS tag is shown capitalised`() {
        tagChoices(listOf(listOf("MARTIAL ARTS"))).single().label shouldBe "Martial arts"
    }

    @Test
    fun `a tap cycles a tag from shown to hidden to cleared`() {
        val preferences = ReikaiLibraryPreferences(InMemoryPreferenceStore())
        val seen = (1..3).map {
            preferences.cycleTag("action")
            preferences.currentTagFilter().let { it.include to it.exclude }
        }
        seen shouldBe listOf(
            setOf("action") to emptySet(),
            emptySet<String>() to setOf("action"),
            emptySet<String>() to emptySet(),
        )
    }

    @Test
    fun `Show only this tag replaces the shown tags and unhides it`() {
        val preferences = ReikaiLibraryPreferences(InMemoryPreferenceStore())
        preferences.filterTagsInclude.set(setOf("drama"))
        preferences.filterTagsExclude.set(setOf("action", "gore"))
        preferences.showOnlyTag("action")
        preferences.currentTagFilter().let { it.include to it.exclude } shouldBe (setOf("action") to setOf("gore"))
    }

    @Test
    fun `Group by tag folds a key the way the tag filter does`() {
        normalizeDynamicKey(" Slice_of--Life ") shouldBe tagKey("slice of life")
    }
}
