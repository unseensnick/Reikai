package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupSearchMetadata
import eu.kanade.tachiyomi.data.backup.models.BackupSearchTag
import eu.kanade.tachiyomi.data.backup.models.BackupSearchTitle
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

/**
 * An adult gallery's metadata, tags and titles come back under the id the restore gives the gallery,
 * which is never the id it had on the device that made the backup. Runs the real restore over real SQL.
 */
class MangaRestoreSearchMetadataTest {

    private val backup = BackupManga(
        source = 1L,
        url = "/g/1/",
        title = "Gallery",
        searchMetadata = BackupSearchMetadata(
            uploader = "uploader",
            extra = "{}",
            extraVersion = 2,
            tags = listOf(BackupSearchTag(namespace = "artist", name = "someone", type = 0)),
            titles = listOf(BackupSearchTitle(title = "Alt title", type = 1)),
        ),
    )

    /** Restores [backup] over a device that already has an unrelated series, so ids cannot line up by chance. */
    private suspend fun <T> restored(read: suspend MangaRestoreHarness.(Long) -> T): T =
        MangaRestoreHarness.create().use { harness ->
            harness.insert(Manga.create().copy(url = "/other/", source = 2L, title = "Other"))
            harness.restorer().restore(listOf(backup), emptyList())
            val id = checkNotNull(harness.mangas.getMangaByUrlAndSourceId("/g/1/", 1L)).id
            harness.read(id)
        }

    @Test
    fun `the gallery's metadata is stored under its restored id`() = runTest {
        restored { id -> metadata.getMetadataById(id)?.uploader } shouldBe "uploader"
    }

    @Test
    fun `the gallery's tags are stored under its restored id`() = runTest {
        restored { id -> metadata.getTagsById(id).map { it.namespace to it.name } } shouldBe
            listOf("artist" to "someone")
    }

    @Test
    fun `the gallery's titles are stored under its restored id`() = runTest {
        restored { id -> metadata.getTitlesById(id).map { it.title } } shouldBe listOf("Alt title")
    }
}
