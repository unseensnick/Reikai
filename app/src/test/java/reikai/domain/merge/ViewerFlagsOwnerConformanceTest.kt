package reikai.domain.merge

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType

/**
 * A merged series keeps one set of reader flags, whichever member a launch opens through, for both
 * content types: read from its settings owner and written to every member. Each reader resolves its
 * group with the type's merge manager and goes through [GroupViewerFlags].
 */
class ViewerFlagsOwnerConformanceTest {

    private val managers = TestMergeManagers(
        memberships = mapOf(
            ContentType.MANGA to linkedMapOf(1L to 7L, 2L to 7L),
            ContentType.NOVELS to linkedMapOf(1L to 7L, 2L to 7L),
        ),
        mergingOn = true,
    )

    private suspend fun flagsOf(type: ContentType, opened: Long): GroupViewerFlags =
        GroupViewerFlags.of(opened, managers.of(type).computeRelatedIds(opened).asList())

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a series opened through a sibling reads its flags from the settings owner`(type: ContentType) = runTest {
        flagsOf(type, opened = 2L).ownerId shouldBe 1L
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a change made through a sibling is written to every member`(type: ContentType) = runTest {
        val written = mutableListOf<Long>()

        flagsOf(type, opened = 2L).write { written += it }

        written shouldBe listOf(1L, 2L)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `an ungrouped series keeps its flags on itself`(type: ContentType) = runTest {
        flagsOf(type, opened = 5L).memberIds shouldBe listOf(5L)
    }
}
