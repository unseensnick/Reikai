package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCustomMangaInfo
import eu.kanade.tachiyomi.data.backup.models.BackupCustomNovelInfo
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.NovelRestorer
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.backup.RestoreHiddenEntries
import reikai.data.local.CombineLocalVolumes
import reikai.domain.library.ContentType
import reikai.domain.library.StoredType
import reikai.domain.library.contentType
import reikai.domain.merge.AlignGroupCategories
import reikai.domain.merge.GatherGroupViewerFlags

/**
 * The steps a library restore runs once its entries are back, in the order they depend on: local volumes regroup
 * chapters across series, merges need every member restored, and hidden is applied over the merged groups. The
 * order is the behaviour, so each step records itself; one sequence serves both types.
 */
class RestoreSequenceConformanceTest {

    @ParameterizedTest(name = "{0}")
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a library restore runs its cross-entry steps in order`(type: ContentType) = runTest {
        val steps = mutableListOf<String>()
        val mangaRestorer = mockk<MangaRestorer>(relaxed = true) {
            coEvery { restore(any(), any()) } answers { steps += "entries" }
            coEvery { restoreCustomInfo(any<Long>(), any(), any()) } answers { steps += "custom info" }
            coEvery { restoreMerges(any(), any(), any()) } answers { if (type == ContentType.MANGA) steps += "merges" }
            coEvery { restoredId(any(), any()) } returns 1L
        }
        val novelRestorer = mockk<NovelRestorer>(relaxed = true) {
            coEvery { restore(any(), any()) } answers { steps += "entries" }
            coEvery { restoreCustomInfo(any<String>(), any(), any()) } answers { steps += "custom info" }
            coEvery { restoreMerges(any(), any()) } answers { if (type == ContentType.NOVELS) steps += "merges" }
            coEvery { restoredId(any(), any()) } returns 1L
        }
        // The other type's library runs too, with nothing in it, so only this type's steps are recorded.
        val combineLocalVolumes = mockk<CombineLocalVolumes> {
            coEvery { restoreOwners(any(), any(), any()) } answers
                { if (firstArg<StoredType>().contentType == type) steps += "local volumes" }
        }
        val align = mockk<AlignGroupCategories> {
            coEvery { align(any()) } answers { if (firstArg<ContentType>() == type) steps += "align" }
        }
        val gather = mockk<GatherGroupViewerFlags> {
            coEvery { gather(any()) } answers { if (firstArg<ContentType>() == type) steps += "gather" }
        }
        val hidden = mockk<RestoreHiddenEntries> {
            coEvery { deviceHidden(any()) } returns emptySet()
            coEvery { restore<Any>(any(), any(), any(), any()) } answers {
                if (firstArg<StoredType>().contentType == type) steps += "hidden"
            }
        }

        restoreEncoded(
            backupOf(type),
            RestoreOptions(libraryEntries = true, categories = false, appSettings = false),
            mangaRestorer = mangaRestorer,
            novelRestorer = novelRestorer,
            combineLocalVolumes = combineLocalVolumes,
            alignGroupCategories = align,
            gatherGroupViewerFlags = gather,
            restoreHiddenEntries = hidden,
        )

        steps shouldBe listOf("entries", "custom info", "local volumes", "merges", "align", "gather", "hidden")
    }

    /** One hidden entry, plus an older root-list custom info row for a series the backup does not list. */
    private fun backupOf(type: ContentType) = when (type) {
        ContentType.MANGA -> Backup(
            backupManga = listOf(BackupManga(source = 1L, url = "a").apply { libraryHidden = true }),
            backupCustomMangaInfo = listOf(BackupCustomMangaInfo(1L, "elsewhere", title = "Edited")),
        )
        else -> Backup(backupManga = emptyList()).apply {
            backupNovels = listOf(BackupNovel(source = "s", url = "a").apply { libraryHidden = true })
            backupCustomNovelInfo = listOf(BackupCustomNovelInfo("s", "elsewhere", title = "Edited"))
        }
    }
}
