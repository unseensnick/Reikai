package reikai.domain.merge

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * The ranking stamp a stored stitch is checked against. It has to change whenever the order the
 * stitch ranks a group's members in changes, and only then: a stamp that moves on an edit that
 * reorders nobody restitches every group in the library for nothing.
 */
class SourceRankingTest {

    private fun group(overridden: Boolean, vararg members: Pair<Long, Long>) =
        members.map { (memberId, source) -> RankedMember(GROUP, memberId, source, overridden) }

    @Test
    fun `a preferred list that orders the members alike stamps alike`() {
        val members = group(false, 1L to 100L, 2L to 200L)

        rankingStamps(members, listOf(100L))[GROUP] shouldBe rankingStamps(members, listOf(100L, 200L))[GROUP]
    }

    @Test
    fun `a preferred source no member uses stamps like no preference`() {
        val members = group(false, 1L to 100L, 2L to 200L)

        rankingStamps(members, listOf(999L))[GROUP] shouldBe rankingStamps(members, emptyList())[GROUP]
    }

    @Test
    fun `an override ordering the members as the preferred list does stamps alike`() {
        val overridden = group(true, 1L to 100L, 2L to 200L)
        val preferred = group(false, 1L to 100L, 2L to 200L)

        rankingStamps(overridden, emptyList())[GROUP] shouldBe rankingStamps(preferred, listOf(100L, 200L))[GROUP]
    }

    @Test
    fun `a reversed override stamps apart from the preferred list`() {
        val overridden = group(true, 2L to 200L, 1L to 100L)
        val preferred = group(false, 1L to 100L, 2L to 200L)

        rankingStamps(overridden, emptyList())[GROUP] shouldNotBe rankingStamps(preferred, listOf(100L, 200L))[GROUP]
    }

    @Test
    fun `promoting an unranked member stamps apart`() {
        val members = group(false, 1L to 100L, 2L to 200L)

        rankingStamps(members, listOf(200L))[GROUP] shouldNotBe rankingStamps(members, emptyList())[GROUP]
    }

    private companion object {
        const val GROUP = 7L
    }
}
