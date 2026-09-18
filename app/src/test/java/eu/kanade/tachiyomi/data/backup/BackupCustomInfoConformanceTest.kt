package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.NovelBackupCreator
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.model.Novel
import reikai.domain.novel.repository.CustomNovelInfoRepository
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.CustomMangaInfoRepository
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * A backup carries read entries that are no longer in the library when that option is on, so each
 * type's custom-info overlay is resolved per row rather than through the favorites. Nothing clears the
 * overlay on unfavoriting, so such a row is reachable and holds the user's own edits. Whether a backup
 * asks for the overlay at all is gated differently per type (see `NovelBackupCreator.novelCustomInfo`).
 */
class BackupCustomInfoConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("creators")
    fun `a custom-info row on a read entry that left the library is still backed up`(creator: CustomInfoCreator) =
        runTest {
            creator.backUp(entryInLibrary = false) shouldBe listOf("/7" to "My own title")
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("creators")
    fun `a custom-info row whose entry is gone is dropped rather than failing the backup`(creator: CustomInfoCreator) =
        runTest {
            creator.backUp(entryInLibrary = null) shouldBe emptyList()
        }

    companion object {
        @JvmStatic
        fun creators() = listOf(MangaCustomInfoCreator(), NovelCustomInfoCreator())
    }
}

/** One type's creator over a single custom-info row titled "My own title" on entry 7 at "/7". */
interface CustomInfoCreator {

    /** [entryInLibrary] null means the entry's row no longer exists. Returns the (url, title) pairs backed up. */
    suspend fun backUp(entryInLibrary: Boolean?): List<Pair<String, String?>>
}

class MangaCustomInfoCreator : CustomInfoCreator {

    override fun toString() = "manga"

    override suspend fun backUp(entryInLibrary: Boolean?): List<Pair<String, String?>> {
        val mangaRepository = mockk<MangaRepository> {
            if (entryInLibrary == null) {
                coEvery { getMangaById(7) } throws NullPointerException("no such row")
            } else {
                coEvery { getMangaById(7) } returns
                    Manga.create().copy(id = 7, url = "/7", source = 1L, favorite = entryInLibrary)
            }
        }
        val customInfo = mockk<CustomMangaInfoRepository> {
            coEvery { getAll() } returns listOf(CustomMangaInfo(mangaId = 7, title = "My own title"))
        }
        val creator = BackupCreator(
            isAutoBackup = false,
            context = mockk(relaxed = true),
            parser = mockk(relaxed = true),
            getFavorites = mockk(relaxed = true),
            backupPreferences = mockk(relaxed = true),
            mangaRepository = mangaRepository,
            mergeGroupRepository = mockk(relaxed = true),
            customMangaInfoRepository = customInfo,
            categoriesBackupCreator = mockk(relaxed = true),
            mangaBackupCreator = mockk(relaxed = true),
            preferenceBackupCreator = mockk(relaxed = true),
            extensionStoresBackupCreator = mockk(relaxed = true),
            sourcesBackupCreator = mockk(relaxed = true),
            backupFileValidator = mockk(relaxed = true),
            novelBackupCreator = mockk(relaxed = true),
            extensionBackupCreator = mockk(relaxed = true),
            feedBackupCreator = mockk(relaxed = true),
        )
        return creator.backupCustomMangaInfo(BackupOptions(libraryEntries = true)).map { it.url to it.title }
    }
}

class NovelCustomInfoCreator : CustomInfoCreator {

    override fun toString() = "novel"

    override suspend fun backUp(entryInLibrary: Boolean?): List<Pair<String, String?>> {
        val novelRepository = mockk<NovelRepository> {
            coEvery { getById(7) } returns
                entryInLibrary?.let { Novel.create().copy(id = 7, url = "/7", source = "src", favorite = it) }
        }
        val customInfo = mockk<CustomNovelInfoRepository> {
            coEvery { getAll() } returns listOf(CustomNovelInfo(novelId = 7, title = "My own title"))
        }
        val creator = NovelBackupCreator(
            novelRepository = novelRepository,
            novelChapterRepository = mockk(),
            categoryRepository = mockk(),
            novelTrackRepository = mockk(),
            mergeGroupRepository = mockk(),
            customNovelInfoRepository = customInfo,
            database = mockk(),
        )
        return creator.novelCustomInfo().map { it.url to it.title }
    }
}
