package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupMangaMergeGroup
import eu.kanade.tachiyomi.data.backup.models.BackupMangaSourceRef
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ReikaiLibraryPreferences
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId

/**
 * Verifies the manga merge re-keying on restore: groups are serialized as stable {url, source} refs
 * (because the live merge prefs hold IDs that change on restore) and rebuilt against the restored
 * manga's fresh IDs. The novel twin of this is covered by NovelBackupRoundTripTest.
 */
class MangaMergeBackupRoundTripTest {

    private fun <T> pref(value: T): Preference<T> = mockk(relaxed = true) { every { get() } returns value }

    private fun restorer(
        getByUrlSource: GetMangaByUrlAndSourceId,
        prefs: ReikaiLibraryPreferences,
    ) = MangaRestorer(
        database = mockk(relaxed = true),
        getCategories = mockk(relaxed = true),
        getMangaByUrlAndSourceId = getByUrlSource,
        getChaptersByMangaId = mockk(relaxed = true),
        updateManga = mockk(relaxed = true),
        getTracks = mockk(relaxed = true),
        insertTrack = mockk(relaxed = true),
        fetchInterval = mockk(relaxed = true),
        reikaiLibraryPreferences = prefs,
        mangaMetadataRepository = mockk(relaxed = true),
    )

    @Test
    fun `merge group is rebuilt against the restored manga ids`() = runTest {
        // A backed-up merge of two manga on different sources, serialized as {url, source} refs.
        val group = BackupMangaMergeGroup(
            refs = listOf(BackupMangaSourceRef("a", 1L), BackupMangaSourceRef("b", 2L)),
        )
        // On restore the two manga come back with fresh ids 10 and 20.
        val getByUrlSource = mockk<GetMangaByUrlAndSourceId> {
            coEvery { await("a", 1L) } returns mockk { every { id } returns 10L }
            coEvery { await("b", 2L) } returns mockk { every { id } returns 20L }
        }
        val mergeSlot = slot<Set<String>>()
        val prefs = mockk<ReikaiLibraryPreferences> {
            every { mangaManualMerges } returns mockk(relaxed = true) {
                every { get() } returns emptySet()
                every { set(capture(mergeSlot)) } returns Unit
            }
            every { mangaManualUnmerges } returns pref(emptySet())
        }

        restorer(getByUrlSource, prefs).restoreMerges(listOf(group), emptyList())

        // Rebuilt against the new ids, sorted and comma-joined as the pref expects.
        mergeSlot.captured shouldBe setOf("10,20")
    }

    @Test
    fun `merge group is dropped when fewer than two members resolve`() = runTest {
        val getByUrlSource = mockk<GetMangaByUrlAndSourceId> {
            coEvery { await("a", 1L) } returns mockk { every { id } returns 10L }
            // The second member's manga wasn't restored (e.g. its source wasn't backed up).
            coEvery { await("b", 2L) } returns null
        }
        val prefs = mockk<ReikaiLibraryPreferences> {
            every { mangaManualMerges } returns mockk(relaxed = true) { every { get() } returns emptySet() }
            every { mangaManualUnmerges } returns pref(emptySet())
        }
        val group = BackupMangaMergeGroup(
            refs = listOf(BackupMangaSourceRef("a", 1L), BackupMangaSourceRef("b", 2L)),
        )

        restorer(getByUrlSource, prefs).restoreMerges(listOf(group), emptyList())

        // Only one member resolved, so no merge is written.
        verify(exactly = 0) { prefs.mangaManualMerges.set(any()) }
    }
}
