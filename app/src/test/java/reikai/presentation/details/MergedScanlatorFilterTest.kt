package reikai.presentation.details

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * A merged manga's scanlator filter: under All it covers every source, since the unified list shows
 * all their chapters; on a chip it covers that source alone; and saving applies only what changed.
 */
class MergedScanlatorFilterTest {

    private val available = mapOf(1L to setOf("Alpha", "Beta"), 2L to setOf("Beta", "Gamma"))
    private val excluded = mapOf(1L to setOf("Alpha"), 2L to setOf("Gamma"))

    @Test
    fun `under All the filter covers every source of the group`() {
        scanlatorTargets(EntryMergeGroupHost.GroupState(longArrayOf(1L, 2L), selected = null)) shouldBe listOf(1L, 2L)
    }

    @Test
    fun `on a chip the filter covers that source alone`() {
        scanlatorTargets(EntryMergeGroupHost.GroupState(longArrayOf(1L, 2L), selected = 2L)) shouldBe listOf(2L)
    }

    @Test
    fun `a scanlator only a sibling has is listed under All`() {
        scanlatorFilterView(listOf(1L, 2L), available, excluded).available shouldBe setOf("Alpha", "Beta", "Gamma")
    }

    @Test
    fun `a scanlator hidden on every source that has it shows as hidden under All`() {
        scanlatorFilterView(listOf(1L, 2L), available, excluded).excluded shouldBe setOf("Alpha", "Gamma")
    }

    @Test
    fun `a scanlator another source still shows is not shown as hidden under All`() {
        val both = mapOf(1L to setOf("Beta"), 2L to setOf("Beta"))
        val hiddenOnOne = mapOf(1L to setOf("Beta"), 2L to emptySet<String>())
        scanlatorFilterView(listOf(1L, 2L), both, hiddenOnOne).excluded shouldBe emptySet()
    }

    @Test
    fun `a hidden scanlator no source has any more is not listed as hidden`() {
        scanlatorFilterView(listOf(1L), mapOf(1L to setOf("Beta")), mapOf(1L to setOf("Gone"))).excluded shouldBe
            emptySet()
    }

    @Test
    fun `a chip lists only its own source's scanlators`() {
        scanlatorFilterView(listOf(2L), available, excluded) shouldBe
            ScanlatorFilterView(available = setOf("Beta", "Gamma"), excluded = setOf("Gamma"))
    }

    @Test
    fun `hiding a scanlator under All hides it on every source`() {
        val shown = setOf("Alpha", "Gamma")
        scanlatorWrites(listOf(1L, 2L), excluded, shown, chosen = shown + "Beta") shouldBe
            mapOf(1L to setOf("Alpha", "Beta"), 2L to setOf("Gamma", "Beta"))
    }

    @Test
    fun `showing a scanlator under All shows it on every source that hid it`() {
        val both = mapOf(1L to setOf("Beta"), 2L to setOf("Beta"))
        scanlatorWrites(listOf(1L, 2L), both, shown = setOf("Beta"), chosen = emptySet()) shouldBe
            mapOf(1L to emptySet(), 2L to emptySet())
    }

    @Test
    fun `saving under All does not spread one source's own hidden scanlator to the others`() {
        val shown = setOf("Alpha", "Gamma")
        // Only Beta changed, so source 2 gains Beta and never gains source 1's Alpha.
        scanlatorWrites(listOf(1L, 2L), excluded, shown, chosen = shown + "Beta").getValue(2L) shouldBe
            setOf("Gamma", "Beta")
    }

    @Test
    fun `a source whose hidden set does not change is not written`() {
        val shown = setOf("Alpha")
        val own = mapOf(1L to setOf("Alpha"), 2L to emptySet())
        // Untick nothing, tick nothing: neither source changes.
        scanlatorWrites(listOf(1L, 2L), own, shown, chosen = shown) shouldBe emptyMap()
    }
}
