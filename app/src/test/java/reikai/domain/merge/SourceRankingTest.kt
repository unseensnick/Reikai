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

    /** A member as the trunk order sees it: its priority, its chapter count, its id. */
    private data class Member(val priority: Int, val chapters: Long, val id: Long)

    private fun leader(vararg members: Member) =
        members.sortedWith(trunkOrder({ it.priority }, { it.chapters }, { it.id })).first().id

    @Test
    fun `a ranked member leads one with more chapters`() {
        leader(Member(priority = 1, chapters = 9, id = 1), Member(priority = 0, chapters = 1, id = 2)) shouldBe 2L
    }

    @Test
    fun `with priority tied the member with more chapters leads`() {
        leader(Member(priority = 0, chapters = 1, id = 1), Member(priority = 0, chapters = 9, id = 2)) shouldBe 2L
    }

    @Test
    fun `with priority and chapters tied the lower id leads`() {
        leader(Member(priority = 0, chapters = 5, id = 2), Member(priority = 0, chapters = 5, id = 1)) shouldBe 1L
    }

    private companion object {
        const val GROUP = 7L
    }
}
