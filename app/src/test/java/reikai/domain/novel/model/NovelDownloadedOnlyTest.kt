package reikai.domain.novel.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelPreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/** The global Downloaded only switch over a novel's chapter list, as manga's `Manga.downloadedFilter` applies it. */
class NovelDownloadedOnlyTest {

    private val prefs = NovelPreferences(InMemoryPreferenceStore())

    /** The novel's own downloaded filter is off, so only the switch can hide chapter 2. */
    private val novel = Novel.create().copy(chapterFlags = NovelChapterFlags.FILTER_LOCAL)

    private fun chapter(id: Long) = NovelChapter(
        id = id,
        novelId = 1L,
        url = "/$id",
        name = "Chapter $id",
        read = false,
        bookmark = false,
        lastTextProgress = 0L,
        chapterNumber = id.toDouble(),
        sourceOrder = id,
        dateFetch = 0L,
        dateUpload = 0L,
        page = "",
    )

    private fun shown(downloadedOnly: Boolean) = listOf(chapter(1), chapter(2)).sortedAndFiltered(
        novel,
        prefs,
        downloadedChapterIds = setOf(1L),
        readInOtherSources = emptySet(),
        bookmarkedInOtherSources = emptySet(),
        downloadedOnly = downloadedOnly,
    ).map { it.id }.sorted()

    @Test
    fun `with the switch on a chapter not on disk is hidden`() {
        shown(downloadedOnly = true) shouldBe listOf(1L)
    }

    @Test
    fun `with the switch off the novel's own filter decides`() {
        shown(downloadedOnly = false) shouldBe listOf(1L, 2L)
    }

    /** The switch is applied when reading, never saved, so the novel's setting survives it. */
    @Test
    fun `the switch leaves the novel's own filter untouched`() {
        novel.effectiveDownloadedFilter(prefs) shouldBe 0L
    }
}
