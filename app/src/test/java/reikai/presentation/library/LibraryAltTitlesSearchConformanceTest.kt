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
 * A series' library card is found by its other titles ([reikai.domain.entry.seriesAltTitles]): a merged
 * series' member titles and the stored alternative titles, on manga and novels alike.
 */
class LibraryAltTitlesSearchConformanceTest {

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a hidden source's title finds the merged card`(type: Type) = runTest {
        type.matches(listOf("Demon Slayer", "Kimetsu no Yaiba"), "kimetsu") shouldBe true
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a stored alternative title finds the card`(type: Type) = runTest {
        type.matches(listOf("Demon Slayer"), "kimetsu", stored = listOf("Kimetsu no Yaiba")) shouldBe true
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `the title term reads the alternative titles too`(type: Type) = runTest {
        type.matches(listOf("Demon Slayer"), "title:yaiba", stored = listOf("Kimetsu no Yaiba")) shouldBe true
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `an unmerged entry under an Edit info title is found by its own source's title`(type: Type) = runTest {
        type.matches(listOf("Kimetsu no Yaiba"), "kimetsu", customTitle = "Demon Slayer", merged = false) shouldBe
            true
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a title no source or stored row has does not find the card`(type: Type) = runTest {
        type.matches(listOf("Demon Slayer", "Kimetsu no Yaiba"), "naruto", stored = listOf("KnY")) shouldBe false
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `the other titles come from the rows the fields were built over, not from the row searched`(type: Type) =
        runTest {
            val card = type.card(listOf("Demon Slayer", "Kimetsu no Yaiba"))

            libraryQueryMatches(QueryNode.from("kimetsu"), card, libraryItemQueryFields(sourceKey = { "" })) shouldBe
                false
        }

    /** Each type builds the card through its own merge collapse, members ranked in the order given. */
    enum class Type {
        MANGA {
            override suspend fun card(titles: List<String>, merged: Boolean): LibraryItem {
                val items = titles.mapIndexed { i, title -> mangaRow(i.toLong(), title) }
                val ids = items.map { it.id }
                return MangaMergeCollapse.collapse(
                    items = items,
                    membership = if (merged) ids.associateWith { GROUP_ID } else emptyMap(),
                    mergingEnabled = true,
                    showMergeSourceIcons = false,
                    resolveSource = { mockk() },
                    badgePrefs = NO_BADGES,
                    overrideRankings = mapOf(GROUP_ID to ids),
                ).single()
            }
        },
        NOVEL {
            override suspend fun card(titles: List<String>, merged: Boolean): LibraryItem {
                val novels = titles.mapIndexed { i, title ->
                    Novel.create().copy(id = i.toLong(), source = "source$i", title = title)
                }
                val ids = novels.map { it.id }
                return NovelMergeCollapse.collapse(
                    library = novels.map(::libraryNovel),
                    membership = if (merged) ids.associateWith { GROUP_ID } else emptyMap(),
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

        abstract suspend fun card(titles: List<String>, merged: Boolean = true): LibraryItem

        /**
         * Whether [query] finds the card of a series whose sources carry [titles], with [stored] and a
         * [customTitle] on its Edit info; only a [merged] one groups its sources.
         */
        suspend fun matches(
            titles: List<String>,
            query: String,
            stored: List<String> = emptyList(),
            customTitle: String? = null,
            merged: Boolean = true,
        ): Boolean {
            val card = card(titles, merged)
            val overlay = mapOf(card.id to LibraryQueryOverlay(title = customTitle, altTitles = stored))
            return libraryQueryMatches(
                QueryNode.from(query),
                card,
                libraryItemQueryFields(sourceKey = { it.id.toString() }, rows = listOf(card), overlay = overlay),
            )
        }
    }

    private companion object {
        const val GROUP_ID = 7L
        val NO_BADGES = LibraryBadgePrefs(false, false, false, false, false)

        fun mangaRow(id: Long, title: String) = LibraryItem(
            libraryManga = LibraryManga(
                manga = Manga.create().copy(id = id, source = 40L + id, title = title),
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
