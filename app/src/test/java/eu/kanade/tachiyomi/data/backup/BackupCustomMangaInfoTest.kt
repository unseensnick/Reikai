package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.CustomMangaInfoRepository
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * A backup carries read entries that are no longer in the library when that option is on, so the
 * custom-info overlay has to be resolved per row rather than through the favorites it used to be
 * keyed by. Nothing clears `custom_manga_info` on unfavoriting, so such a row is reachable and its
 * title, author and cover are the user's own edits.
 */
class BackupCustomMangaInfoTest {

    private val mangaRepository = mockk<MangaRepository>()
    private val customMangaInfoRepository = mockk<CustomMangaInfoRepository>()

    private val creator = BackupCreator(
        isAutoBackup = false,
        context = mockk(relaxed = true),
        parser = mockk(relaxed = true),
        getFavorites = mockk(relaxed = true),
        backupPreferences = mockk(relaxed = true),
        mangaRepository = mangaRepository,
        mergeGroupRepository = mockk(relaxed = true),
        customMangaInfoRepository = customMangaInfoRepository,
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

    @Test
    fun `a custom-info row on a read entry that left the library is still backed up`() = runTest {
        val unfavorited = Manga.create().copy(id = 7, url = "/7", source = 1L, favorite = false)
        coEvery { customMangaInfoRepository.getAll() } returns listOf(
            CustomMangaInfo(mangaId = 7, title = "My own title"),
        )
        coEvery { mangaRepository.getMangaById(7) } returns unfavorited

        val backed = creator.backupCustomMangaInfo(BackupOptions(libraryEntries = true))

        backed shouldHaveSize 1
        backed.single().title shouldBe "My own title"
        backed.single().url shouldBe "/7"
    }

    @Test
    fun `a custom-info row whose manga row is gone is dropped rather than failing the backup`() = runTest {
        coEvery { customMangaInfoRepository.getAll() } returns listOf(
            CustomMangaInfo(mangaId = 404, title = "orphan"),
        )
        coEvery { mangaRepository.getMangaById(404) } throws NullPointerException("no such row")

        creator.backupCustomMangaInfo(BackupOptions(libraryEntries = true)) shouldHaveSize 0
    }
}
