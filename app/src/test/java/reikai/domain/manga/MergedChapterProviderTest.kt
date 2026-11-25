package reikai.domain.manga

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.MergeGroupRepository
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.manga.model.Manga

/**
 * Which members the provider loads. The reading order its merged list renders is
 * RenderMergedReadingOrderTest's, which `merged` hands straight to.
 */
class MergedChapterProviderTest {

    @Test
    @DisplayName("an anchor that left the library loads on its own, so the reader can find it")
    fun anchorOutsideLibraryLoadsStandalone() = runTest {
        val preferences = ReikaiLibraryPreferences(EmittingPreferenceStore())
        val repository = mockk<MergeGroupRepository>(relaxed = true) {
            coEvery { getGroupId(ContentType.MANGA, 1L) } returns 7L
            coEvery { getFavoriteMembers(ContentType.MANGA, 7L) } returns listOf(2L, 3L)
        }
        val provider = MergedChapterProvider(
            getMangaWithChapters = mockk(relaxed = true),
            mergeManager = MangaMergeManager(repository, preferences, onMerged = {}) {},
            sourceManager = mockk(relaxed = true),
            reikaiLibraryPreferences = preferences,
            reconcile = mockk(relaxed = true),
        )

        provider.load(Manga.create().copy(id = 1L)).mangaById.keys shouldBe setOf(1L)
    }
}
