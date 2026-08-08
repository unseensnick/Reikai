package reikai.presentation.recents

import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.updates.UpdatesItem
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.data.coil.NovelCover
import reikai.domain.entry.EntryId
import reikai.domain.novel.model.NovelHistoryWithRelations
import reikai.domain.novel.model.NovelUpdateWithRelations
import reikai.domain.recents.RecentlyAddedManga
import reikai.domain.recents.RecentlyAddedNovel
import reikai.presentation.updates.NovelUpdatesItem
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.model.UpdatesWithRelations
import java.util.Date

/**
 * The adapters' row mapping, twinned per content type. Two things it pins that nothing else can: the
 * two engines' timestamps arrive in different types (`java.util.Date` on manga history, `Long` on
 * novel history) and must leave as one, and a raw row id must never become an identity a mixed feed
 * could confuse with the other type's.
 */
class RecentsMappingTest {

    private val mangaCover = MangaCover(
        mangaId = 7,
        sourceId = 1,
        isMangaFavorite = true,
        url = null,
        lastModified = 0,
    )
    private val novelCover = NovelCover(
        url = null,
        site = null,
        isNovelFavorite = true,
        lastModified = 0,
        novelId = 7,
    )

    @Test
    fun `a manga update maps to the updated lane keyed as manga`() {
        val item = mangaUpdate(mangaId = 7, chapterId = 70, dateFetch = 1000)

        val mapped = item.toRecentsItem()

        mapped.entryId shouldBe EntryId.Manga(7)
    }

    @Test
    fun `a novel update maps to the updated lane keyed as novel`() {
        val item = novelUpdate(novelId = 7, chapterId = 70, dateFetch = 1000)

        val mapped = item.toRecentsItem()

        mapped.entryId shouldBe EntryId.Novel(7)
    }

    @Test
    fun `a manga and a novel sharing a row id are not the same entry`() {
        val manga = mangaUpdate(mangaId = 7, chapterId = 70, dateFetch = 1000).toRecentsItem()
        val novel = novelUpdate(novelId = 7, chapterId = 70, dateFetch = 1000).toRecentsItem()

        (manga.entryId == novel.entryId) shouldBe false
    }

    @Test
    fun `a manga and a novel sharing a chapter row id are not the same chapter`() {
        val manga = mangaUpdate(mangaId = 7, chapterId = 70, dateFetch = 1000).toRecentsItem()
        val novel = novelUpdate(novelId = 7, chapterId = 70, dateFetch = 1000).toRecentsItem()

        val chapters = setOf(
            (manga.lane as RecentsLane.Updated).chapter,
            (novel.lane as RecentsLane.Updated).chapter,
        )

        chapters.size shouldBe 2
    }

    @Test
    fun `manga history's Date timestamp arrives as epoch millis`() {
        val row = HistoryWithRelations(
            id = 1,
            chapterId = 70,
            mangaId = 7,
            title = "t",
            chapterNumber = 1.0,
            readAt = Date(4321),
            readDuration = 0,
            coverData = mangaCover,
        )

        row.toRecentsItem().timestamp shouldBe 4321L
    }

    @Test
    fun `novel history's Long timestamp arrives as epoch millis`() {
        val row = NovelHistoryWithRelations(
            id = 1,
            chapterId = 70,
            novelId = 7,
            title = "t",
            chapterNumber = 1.0,
            readAt = 4321,
            readDuration = 0,
            coverData = novelCover,
        )

        row.toRecentsItem().timestamp shouldBe 4321L
    }

    @Test
    fun `a manga never read carries no timestamp rather than a null one`() {
        val row = HistoryWithRelations(
            id = 1,
            chapterId = 70,
            mangaId = 7,
            title = "t",
            chapterNumber = 1.0,
            readAt = null,
            readDuration = 0,
            coverData = mangaCover,
        )

        row.toRecentsItem().timestamp shouldBe 0L
    }

    @Test
    fun `a novel never read carries no timestamp rather than a null one`() {
        val row = NovelHistoryWithRelations(
            id = 1,
            chapterId = 70,
            novelId = 7,
            title = "t",
            chapterNumber = 1.0,
            readAt = null,
            readDuration = 0,
            coverData = novelCover,
        )

        row.toRecentsItem().timestamp shouldBe 0L
    }

    @Test
    fun `a recently added manga has no chapter to open`() {
        val row = RecentlyAddedManga(mangaId = 7, title = "t", dateAdded = 99, coverData = mangaCover)

        row.toRecentsItem().lane shouldBe RecentsLane.Added
    }

    @Test
    fun `a recently added novel has no chapter to open`() {
        val row = RecentlyAddedNovel(
            novelId = 7,
            title = "t",
            source = "s",
            url = "u",
            dateAdded = 99,
            coverData = novelCover,
        )

        row.toRecentsItem().lane shouldBe RecentsLane.Added
    }

    private fun mangaUpdate(mangaId: Long, chapterId: Long, dateFetch: Long) = UpdatesItem(
        update = UpdatesWithRelations(
            mangaId = mangaId,
            mangaTitle = "t",
            chapterId = chapterId,
            chapterName = "c",
            scanlator = null,
            chapterUrl = "u",
            read = false,
            bookmark = false,
            lastPageRead = 0,
            sourceId = 1,
            dateFetch = dateFetch,
            coverData = mangaCover,
        ),
        downloadStateProvider = { Download.State.NOT_DOWNLOADED },
        downloadProgressProvider = { 0 },
    )

    private fun novelUpdate(novelId: Long, chapterId: Long, dateFetch: Long) = NovelUpdatesItem(
        update = NovelUpdateWithRelations(
            novelId = novelId,
            novelTitle = "t",
            chapterId = chapterId,
            chapterName = "c",
            chapterUrl = "u",
            read = false,
            bookmark = false,
            lastTextProgress = 0,
            source = "s",
            dateFetch = dateFetch,
            coverData = novelCover,
            novelUrl = "nu",
        ),
        downloadState = Download.State.NOT_DOWNLOADED,
    )
}
