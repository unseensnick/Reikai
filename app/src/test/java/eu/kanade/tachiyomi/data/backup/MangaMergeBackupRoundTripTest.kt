package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupMangaMergeGroup
import eu.kanade.tachiyomi.data.backup.models.BackupMangaSourceRef
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.domain.merge.MergeGroupRepository
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId

/**
 * Verifies the manga merge re-keying on restore: groups are serialized as stable {url, source} refs
 * (because entry ids change on restore) and materialized into the merge_group tables against the
 * restored manga's fresh ids. The novel twin is covered by NovelBackupRoundTripTest.
 */
class MangaMergeBackupRoundTripTest {

    private fun restorer(
        getByUrlSource: GetMangaByUrlAndSourceId,
        repository: MergeGroupRepository,
    ) = MangaRestorer(
        database = mockk(relaxed = true),
        getCategories = mockk(relaxed = true),
        getMangaByUrlAndSourceId = getByUrlSource,
        getChaptersByMangaId = mockk(relaxed = true),
        updateManga = mockk(relaxed = true),
        getTracks = mockk(relaxed = true),
        insertTrack = mockk(relaxed = true),
        fetchInterval = mockk(relaxed = true),
        mergeGroupRepository = repository,
        mangaMetadataRepository = mockk(relaxed = true),
        setCustomMangaInfo = mockk(relaxed = true),
    )

    @Test
    fun `merge group is materialized against the restored manga ids`() = runTest {
        // A backed-up merge of two manga on different sources, serialized as {url, source} refs.
        val group = BackupMangaMergeGroup(
            refs = listOf(BackupMangaSourceRef("a", 1L), BackupMangaSourceRef("b", 2L)),
        )
        // On restore the two manga come back with fresh ids 10 and 20.
        val getByUrlSource = mockk<GetMangaByUrlAndSourceId> {
            coEvery { await("a", 1L) } returns mockk { every { id } returns 10L }
            coEvery { await("b", 2L) } returns mockk { every { id } returns 20L }
        }
        val repository = mockk<MergeGroupRepository>(relaxed = true)

        restorer(getByUrlSource, repository).restoreMerges(listOf(group))

        // Materialized against the new ids via the repository.
        coVerify { repository.merge(ContentType.MANGA, listOf(10L, 20L)) }
    }

    @Test
    fun `merge group is skipped when fewer than two members resolve`() = runTest {
        val getByUrlSource = mockk<GetMangaByUrlAndSourceId> {
            coEvery { await("a", 1L) } returns mockk { every { id } returns 10L }
            // The second member's manga wasn't restored (e.g. its source wasn't backed up).
            coEvery { await("b", 2L) } returns null
        }
        val repository = mockk<MergeGroupRepository>(relaxed = true)
        val group = BackupMangaMergeGroup(
            refs = listOf(BackupMangaSourceRef("a", 1L), BackupMangaSourceRef("b", 2L)),
        )

        restorer(getByUrlSource, repository).restoreMerges(listOf(group))

        // Only one member resolved, so no group is created.
        coVerify(exactly = 0) { repository.merge(any(), any()) }
    }
}
