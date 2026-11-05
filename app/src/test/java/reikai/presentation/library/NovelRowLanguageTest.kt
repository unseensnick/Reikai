package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.Novel
import reikai.novel.local.LocalNovelSource
import reikai.presentation.library.novels.NovelMergeCollapse
import reikai.presentation.library.novels.toLibraryRow

/** A novel row's language: a local series' own, read from its files, else its source's. */
class NovelRowLanguageTest {

    private suspend fun row(source: String, url: String, languages: Map<String, String>): LibraryItem {
        val novel = Novel.create().copy(id = 1L, source = source, url = url, title = "Book")
        val library = listOf(
            LibraryNovel(
                novel = novel,
                categories = emptyList(),
                totalChapters = 0,
                readCount = 0,
                bookmarkCount = 0,
                downloadCount = 0,
                latestUpload = null,
                chapterFetchedAt = null,
                lastRead = null,
            ),
        )
        return NovelMergeCollapse.collapse(library, membership = emptyMap(), mergingEnabled = false).single()
            .toLibraryRow(
                badgePrefs = LibraryBadgePrefs(false, false, false, language = true, source = false),
                showSourceIcons = false,
                querySource = {
                    LibraryQuerySource(it, name = it, language = "other", isLocal = LocalNovelSource.isLocal(it))
                },
                localLanguages = languages,
                sourceBadge = { SourceBadge.Generic },
            )
    }

    @Test
    fun `a local novel shows the language its book names`() = runTest {
        row(LocalNovelSource.ID, "Book", mapOf("Book" to "ja-JP")).badges.sourceLanguage shouldBe "ja"
    }

    @Test
    fun `a local novel searches by the language its book names`() = runTest {
        row(LocalNovelSource.ID, "Book", mapOf("Book" to "ja-JP")).sourceLanguage shouldBe "ja"
    }

    @Test
    fun `a local novel whose book names no language shows none`() = runTest {
        row(LocalNovelSource.ID, "Book", emptyMap()).badges.sourceLanguage shouldBe ""
    }

    @Test
    fun `an online novel keeps its source's language`() = runTest {
        row("plugin", "Book", mapOf("Book" to "ja")).sourceLanguage shouldBe "other"
    }
}
