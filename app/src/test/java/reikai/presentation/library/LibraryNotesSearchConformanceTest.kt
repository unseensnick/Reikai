package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.domain.library.model.search.QueryNode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.Novel
import reikai.presentation.library.novels.NovelMergeCollapse
import reikai.presentation.library.novels.toLibraryRow
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

/**
 * A merged series' library card is found by the notes of any of its sources, since each source chip keeps
 * its own, on manga and novels alike. The card's own notes still count, and a group none of whose sources
 * has notes is the one `notes:""` asks for.
 */
class LibraryNotesSearchConformanceTest {

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a hidden source's notes find the merged card`(type: Type) = runTest {
        type.matches(listOf("", "read on the second site"), "notes:second") shouldBe true
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a bare word finds the merged card by a hidden source's notes`(type: Type) = runTest {
        type.matches(listOf("", "read on the second site"), "second") shouldBe true
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `the card's own notes still find it`(type: Type) = runTest {
        type.matches(listOf("shown source note", ""), "notes:shown") shouldBe true
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `notes no source has do not find the card`(type: Type) = runTest {
        type.matches(listOf("first", "second"), "notes:third") shouldBe false
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a group with notes only on a hidden source is not one with no notes`(type: Type) = runTest {
        type.matches(listOf("", "second"), "notes:\"\"") shouldBe false
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a group with no notes on any source is one with no notes`(type: Type) = runTest {
        type.matches(listOf("", ""), "notes:\"\"") shouldBe true
    }

    /** Each type builds the card through its own merge collapse, members ranked in the order given. */
    enum class Type {
        MANGA {
            override suspend fun card(notes: List<String>): LibraryItem {
                val items = notes.mapIndexed { i, note -> mangaRow(i.toLong(), note) }
                val ids = items.map { it.id }
                return MangaMergeCollapse.collapse(
                    items = items,
                    membership = ids.associateWith { GROUP_ID },
                    mergingEnabled = true,
                    showMergeSourceIcons = false,
                    resolveSource = { mockk() },
                    badgePrefs = NO_BADGES,
                    overrideRankings = mapOf(GROUP_ID to ids),
                ).single()
            }
        },
        NOVEL {
            override suspend fun card(notes: List<String>): LibraryItem {
                val novels = notes.mapIndexed { i, note ->
                    Novel.create().copy(id = i.toLong(), source = "source$i", notes = note)
                }
                val ids = novels.map { it.id }
                return NovelMergeCollapse.collapse(
                    library = novels.map(::libraryNovel),
                    membership = ids.associateWith { GROUP_ID },
                    mergingEnabled = true,
                    overrideRankings = mapOf(GROUP_ID to ids),
                ).single().toLibraryRow(
                    badgePrefs = NO_BADGES,
                    showSourceIcons = false,
                    querySource = { LibraryQuerySource(it, "source", "en", isLocal = false) },
                    localLanguages = emptyMap(),
                    sourceBadge = { SourceBadge.Generic },
                )
            }
        },
        ;

        abstract suspend fun card(notes: List<String>): LibraryItem

        /** Whether [query] finds the card of a series whose sources carry [notes], the first one shown. */
        suspend fun matches(notes: List<String>, query: String): Boolean = libraryQueryMatches(
            QueryNode.from(query),
            card(notes),
            libraryItemQueryFields(sourceKey = { it.id.toString() }),
        )
    }

    private companion object {
        const val GROUP_ID = 7L
        val NO_BADGES = LibraryBadgePrefs(false, false, false, false, false)

        fun mangaRow(id: Long, notes: String) = LibraryItem(
            libraryManga = LibraryManga(
                manga = Manga.create().copy(id = id, source = 40L + id, notes = notes),
                categories = emptyList(),
                totalChapters = 0,
                readCount = 0,
                bookmarkCount = 0,
                latestUpload = null,
                chapterFetchedAt = null,
                lastRead = null,
            ),
            downloadCount = 0,
            unreadCount = 0,
            isLocal = false,
            badges = LibraryItem.Badges(downloadCount = 0, unreadCount = 0, isLocal = false, sourceLanguage = ""),
        )

        fun libraryNovel(novel: Novel) = LibraryNovel(
            novel = novel,
            categories = emptyList(),
            totalChapters = 0,
            readCount = 0,
            bookmarkCount = 0,
            downloadCount = 0,
            latestUpload = null,
            chapterFetchedAt = null,
            lastRead = null,
        )
    }
}
