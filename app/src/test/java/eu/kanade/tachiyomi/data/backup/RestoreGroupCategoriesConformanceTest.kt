package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
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
import reikai.domain.library.ContentType
import reikai.domain.merge.AlignGroupCategories
import java.util.Collections

/**
 * A restore writes each entry's own backed-up categories, so once a type's groups are restored every
 * group is put back in its first library member's categories. Runs the real BackupRestorer over an
 * encoded backup holding one entry of each type.
 */
class RestoreGroupCategoriesConformanceTest {

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a restore aligns the type's groups' categories once its groups are restored`(type: ContentType) =
        runTest {
            val steps = Collections.synchronizedList(mutableListOf<String>())
            restoreEncoded(
                Backup(
                    backupManga = listOf(BackupManga(source = 1, url = "a", title = "Manga")),
                    backupNovels = listOf(BackupNovel(source = "s1", url = "a", title = "Novel")),
                ),
                RestoreOptions(libraryEntries = true, categories = true, appSettings = false),
                mangaRestorer = mockk<MangaRestorer>(relaxed = true) {
                    coEvery { restoreMerges(any(), any()) } answers { steps += "groups ${ContentType.MANGA}" }
                },
                novelRestorer = mockk<NovelRestorer>(relaxed = true) {
                    coEvery { restoreMerges(any(), any()) } answers { steps += "groups ${ContentType.NOVELS}" }
                },
                alignGroupCategories = mockk<AlignGroupCategories> {
                    coEvery { align(any()) } answers { steps += "align ${firstArg<ContentType>()}" }
                },
            )

            steps.filter { it.endsWith(" $type") } shouldBe listOf("groups $type", "align $type")
        }
}
