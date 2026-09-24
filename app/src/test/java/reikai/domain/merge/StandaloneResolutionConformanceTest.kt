package reikai.domain.merge

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.MangaMergeManager
import reikai.domain.novel.NovelMergeManager

/**
 * An entry that left the library while its group stays resolves on its own, for both content types.
 * Opened from History or Browse, it must not borrow the group's other members: the reader looks the
 * opened entry up among the resolved ids, and its tracker rows are its own again.
 */
class StandaloneResolutionConformanceTest {

    private fun manager(type: ContentType, repository: MergeGroupRepository): EntryMergeManager {
        val preferences = mockk<ReikaiLibraryPreferences> {
            every { seriesMergingEnabled } returns mockk(relaxed = true) { every { get() } returns true }
        }
        return when (type) {
            ContentType.MANGA -> MangaMergeManager(repository, preferences) {}
            else -> NovelMergeManager(repository, preferences) {}
        }
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `an entry outside the library resolves to itself while its group keeps two members`(type: ContentType) =
        runTest {
            val repository = mockk<MergeGroupRepository> {
                coEvery { getGroupId(type, 1L) } returns 7L
                coEvery { getFavoriteMembers(type, 7L) } returns listOf(2L, 3L)
            }

            manager(type, repository).computeRelatedIds(1L).toList() shouldBe listOf(1L)
        }
}
