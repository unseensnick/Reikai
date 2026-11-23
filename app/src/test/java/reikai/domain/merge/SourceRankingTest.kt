package reikai.domain.merge

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * The ranking stamp a stored stitch is checked against. It has to change whenever the order the
 * stitch ranks a group's members in changes, and only then: a stamp that moves on an edit that
 * reorders nobody restitches every group in the library for nothing.
 */
class SourceRankingTest {

    private fun group(overridden: Boolean, vararg members: Pair<Long, Long>) =
        members.map { (memberId, source) -> RankedMember(GROUP, memberId, source, overridden) }

    @Test
    fun `a preferred member with no chapters still ranks first, and the walk starts after it`() {
        val ranking = GroupRanking<String, Long> { it.size.toLong() }
        val chapters = mapOf(1L to emptyList(), 2L to listOf("a"))
        val sources = mapOf(1L to 100L, 2L to 200L)

        val ranked = ranking.rank(chapters, sources, listOf(100L))
        val walked = ranking.walkOrder(chapters, sources, listOf(100L), emptyList())

        ranked.first().memberId to walked.first().memberId shouldBe (1L to 2L)
    }

    // Both stitchers rank through this one kernel, so their aggregation tests keep only a wiring case each.
    @ParameterizedTest(name = "{0}")
    @MethodSource("rankings")
    fun `a group's override, then the preferred sources, then chapter count decide the lead`(
        name: String,
        chapterCounts: Map<Long, Int>,
        sources: Map<Long, Long>,
        preferredSourceIds: List<Long>,
        memberRanking: List<Long>,
        expected: List<Long>,
    ) {
        val ranking = GroupRanking<String, Long> { it.size.toLong() }
        val chapters = chapterCounts.mapValues { (_, count) -> List(count) { "c$it" } }

        ranking.rank(chapters, sources, preferredSourceIds, memberRanking).map { it.memberId } shouldBe expected
    }

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

    companion object {
        private const val GROUP = 7L
        private val twoSources = mapOf(1L to 100L, 2L to 200L)

        @JvmStatic
        fun rankings() = listOf(
            Arguments.of(
                "a preferred source leads one with more chapters",
                mapOf(1L to 5, 2L to 3),
                twoSources,
                listOf(200L),
                emptyList<Long>(),
                listOf(2L, 1L),
            ),
            Arguments.of(
                "preferred sources lead by list index, an unranked one after them however many chapters it has",
                mapOf(1L to 2, 2L to 3, 3L to 4),
                mapOf(1L to 100L, 2L to 200L, 3L to 300L),
                listOf(200L, 100L),
                emptyList<Long>(),
                listOf(2L, 1L, 3L),
            ),
            Arguments.of(
                "a preferred source no member uses leaves chapter count to decide",
                mapOf(1L to 5, 2L to 3),
                twoSources,
                listOf(999L),
                emptyList<Long>(),
                listOf(1L, 2L),
            ),
            Arguments.of(
                "a group's override leads over chapter count",
                mapOf(1L to 5, 2L to 3),
                emptyMap<Long, Long>(),
                emptyList<Long>(),
                listOf(2L, 1L),
                listOf(2L, 1L),
            ),
            Arguments.of(
                "a group's override wins over the preferred list",
                mapOf(1L to 3, 2L to 2),
                twoSources,
                listOf(100L),
                listOf(2L, 1L),
                listOf(2L, 1L),
            ),
            // Two library rows from one source: a source-id ranking cannot tell them apart.
            Arguments.of(
                "an override orders two members sharing one source as given",
                mapOf(1L to 2, 2L to 3),
                mapOf(1L to 100L, 2L to 100L),
                emptyList<Long>(),
                listOf(1L, 2L),
                listOf(1L, 2L),
            ),
            Arguments.of(
                "the reversed override flips two members sharing one source",
                mapOf(1L to 2, 2L to 3),
                mapOf(1L to 100L, 2L to 100L),
                emptyList<Long>(),
                listOf(2L, 1L),
                listOf(2L, 1L),
            ),
        )
    }
}
