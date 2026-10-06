package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import reikai.domain.entry.EntryId
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

/** The continue-reading button every library cell draws, in both views and for both content types. */
class LibraryItemCellTest {

    private fun row(unread: Long, novel: Boolean): LibraryItem = LibraryItem(
        libraryManga = LibraryManga(
            manga = Manga.create().copy(id = 7L),
            categories = emptyList(),
            totalChapters = 0,
            readCount = 0,
            bookmarkCount = 0,
            latestUpload = 0,
            chapterFetchedAt = 0,
            lastRead = 0,
        ),
        downloadCount = 0,
        unreadCount = unread,
        isLocal = false,
        badges = LibraryItem.Badges(downloadCount = 0, unreadCount = unread, isLocal = false, sourceLanguage = ""),
        entryId = if (novel) EntryId.Novel(7L) else EntryId.Manga(7L),
    )

    @ParameterizedTest(name = "novel = {0}")
    @ValueSource(booleans = [false, true])
    fun `a row with nothing unread draws no continue button`(novel: Boolean) {
        continueReadingFor(row(unread = 0, novel = novel)) {} shouldBe null
    }

    @ParameterizedTest(name = "novel = {0}")
    @ValueSource(booleans = [false, true])
    fun `a row with unread chapters resumes that row`(novel: Boolean) {
        val row = row(unread = 3, novel = novel)
        var resumed: LibraryItem? = null
        continueReadingFor(row) { resumed = it }?.invoke()
        resumed shouldBe row
    }

    @Test
    fun `no handler draws no continue button`() {
        continueReadingFor(row(unread = 3, novel = false), null) shouldBe null
    }
}
