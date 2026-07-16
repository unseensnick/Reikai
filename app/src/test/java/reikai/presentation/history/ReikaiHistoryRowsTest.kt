package reikai.presentation.history

import eu.kanade.presentation.history.HistoryUiModel
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.data.coil.NovelCover
import reikai.domain.library.ContentType
import reikai.domain.novel.model.NovelHistoryWithRelations
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.model.MangaCover
import java.util.Date

class ReikaiHistoryRowsTest {

    private fun mangaItem(id: Long, readAt: Long) = HistoryUiModel.Item(
        HistoryWithRelations(
            id = id,
            chapterId = id,
            mangaId = id,
            title = "Manga $id",
            chapterNumber = 1.0,
            readAt = Date(readAt),
            readDuration = 0L,
            coverData = MangaCover(mangaId = id, sourceId = 0L, isMangaFavorite = true, url = null, lastModified = 0L),
        ),
    )

    private fun novelHistory(id: Long, readAt: Long) = NovelHistoryWithRelations(
        id = id,
        chapterId = id,
        novelId = id,
        title = "Novel $id",
        chapterNumber = 1.0,
        readAt = readAt,
        readDuration = 0L,
        coverData = NovelCover(url = null, site = null, isNovelFavorite = true, lastModified = 0L, novelId = id),
    )

    @Test
    fun `ALL interleaves manga and novel, newest first`() {
        val rows = buildHistoryRows(
            ContentType.ALL,
            mangaList = listOf(mangaItem(1, readAt = 2000L)),
            novelList = listOf(novelHistory(2, readAt = 1000L)),
        )
        val items = rows.filterNot { it is HistoryRow.Header }
        (items[0] as HistoryRow.Manga).item.mangaId shouldBe 1L
    }

    @Test
    fun `a novel read more recently sorts above older manga`() {
        val rows = buildHistoryRows(
            ContentType.ALL,
            mangaList = listOf(mangaItem(1, readAt = 1000L)),
            novelList = listOf(novelHistory(2, readAt = 2000L)),
        )
        val items = rows.filterNot { it is HistoryRow.Header }
        (items[0] as HistoryRow.Novel).item.novelId shouldBe 2L
    }

    @Test
    fun `the NOVELS chip excludes manga rows`() {
        val rows = buildHistoryRows(
            ContentType.NOVELS,
            mangaList = listOf(mangaItem(1, readAt = 2000L)),
            novelList = listOf(novelHistory(2, readAt = 1000L)),
        )
        rows.any { it is HistoryRow.Manga } shouldBe false
    }

    @Test
    fun `the MANGA chip excludes novel rows`() {
        val rows = buildHistoryRows(
            ContentType.MANGA,
            mangaList = listOf(mangaItem(1, readAt = 2000L)),
            novelList = listOf(novelHistory(2, readAt = 1000L)),
        )
        rows.any { it is HistoryRow.Novel } shouldBe false
    }

    @Test
    fun `the list opens with a date header`() {
        val rows = buildHistoryRows(
            ContentType.ALL,
            mangaList = listOf(mangaItem(1, readAt = 2000L)),
            novelList = emptyList(),
        )
        (rows.first() is HistoryRow.Header) shouldBe true
    }

    @Test
    fun `the Date and Long read times map to the same timestamp string`() {
        val epoch = 1_700_000_000_000L
        val manga = mangaItem(1, epoch).item.toEntryHistoryRowUi()
        val novel = novelHistory(1, epoch).toEntryHistoryRowUi()
        manga.readAt shouldBe novel.readAt
    }

    @Test
    fun `a real read time maps to a non-empty timestamp`() {
        novelHistory(1, readAt = 1_700_000_000_000L).toEntryHistoryRowUi().readAt.isNotEmpty() shouldBe true
    }
}
