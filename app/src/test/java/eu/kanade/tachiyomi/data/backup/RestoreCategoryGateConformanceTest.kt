package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelCategory
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.NovelRestorer
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * The categories a restore hands each entry to file it under, over the real BackupRestorer and an
 * encoded backup: the backup's own with Categories on, none with it off, so an entry is never filed
 * under a same-named category the device already had.
 */
class RestoreCategoryGateConformanceTest {

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `with Categories off an entry is filed under no category`(type: Type) = runTest {
        type.categoryNamesHanded(categories = false) shouldBe emptyList()
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `with Categories on an entry is filed under the backup's categories`(type: Type) = runTest {
        type.categoryNamesHanded(categories = true) shouldBe listOf("Reading")
    }

    enum class Type {
        MANGA {
            override suspend fun categoryNamesHanded(categories: Boolean): List<String> {
                var handed: List<String>? = null
                val restorer = mockk<MangaRestorer>(relaxed = true) {
                    coEvery { restore(any(), any()) } answers {
                        handed = secondArg<List<BackupCategory>>().map { it.name }
                    }
                }
                restoreEncoded(
                    Backup(
                        backupManga = listOf(BackupManga(source = 1, url = "a", title = "T", categories = listOf(1L))),
                        backupCategories = listOf(BackupCategory(name = "Reading", order = 1)),
                    ),
                    options(categories),
                    mangaRestorer = restorer,
                )
                return handed!!
            }
        },
        NOVELS {
            override suspend fun categoryNamesHanded(categories: Boolean): List<String> {
                var handed: List<String>? = null
                val restorer = mockk<NovelRestorer>(relaxed = true) {
                    coEvery { restore(any(), any()) } answers {
                        handed = secondArg<List<BackupNovelCategory>>().map { it.name }
                    }
                }
                restoreEncoded(
                    Backup(
                        backupManga = emptyList(),
                        backupNovels = listOf(
                            BackupNovel(source = "s", url = "a", title = "T", categories = listOf(1L)),
                        ),
                        backupNovelCategories = listOf(BackupNovelCategory(name = "Reading", order = 1)),
                    ),
                    options(categories),
                    novelRestorer = restorer,
                )
                return handed!!
            }
        },
        ;

        /** Restores one entry filed in one backed-up category; the names handed to its restorer. */
        abstract suspend fun categoryNamesHanded(categories: Boolean): List<String>

        protected fun options(categories: Boolean) =
            RestoreOptions(libraryEntries = true, categories = categories, appSettings = false)
    }
}
