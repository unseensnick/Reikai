package reikai.presentation.history

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.data.coil.NovelCover
import reikai.domain.novel.model.NovelHistoryWithRelations
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.model.MangaCover
import java.util.Date

class EntryHistoryRowUiTest {

    private fun mangaHistory(id: Long, readAt: Long) = HistoryWithRelations(
        id = id,
        chapterId = id,
        mangaId = id,
        title = "Manga $id",
        chapterNumber = 1.0,
        readAt = Date(readAt),
        readDuration = 0L,
        coverData = MangaCover(mangaId = id, sourceId = 0L, isMangaFavorite = true, url = null, lastModified = 0L),
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
    fun `the Date and Long read times map to the same timestamp string`() {
        val epoch = 1_700_000_000_000L
        val manga = mangaHistory(1, epoch).toEntryHistoryRowUi()
        val novel = novelHistory(1, epoch).toEntryHistoryRowUi()
        manga.readAt shouldBe novel.readAt
    }

    @Test
    fun `a real read time maps to a non-empty timestamp`() {
        novelHistory(1, readAt = 1_700_000_000_000L).toEntryHistoryRowUi().readAt.isNotEmpty() shouldBe true
    }
}
