package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.domain.library.model.search.QueryNode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.TagAlias
import reikai.domain.library.TagAliases
import reikai.domain.library.TagFilter
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.Novel
import reikai.presentation.library.novels.NovelMergeCollapse
import reikai.presentation.library.novels.toLibraryRow
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

/**
 * The library's tag filter reads the same tags on manga and novels: every source's tags of a merged series,
 * or the series' Edit info tags where set, through the one filter binding both libraries call.
 */
class LibraryTagFilterConformanceTest {

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a merged series is shown by a tag only a hidden member carries`(type: Type) = runTest {
        type.passes(listOf(listOf("Action"), listOf("Romance")), TagFilter(include = setOf("romance"))) shouldBe true
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a merged series is hidden by a tag only a hidden member carries`(type: Type) = runTest {
        type.passes(listOf(listOf("Action"), listOf("Gore")), TagFilter(exclude = setOf("gore"))) shouldBe false
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `Edit info tags replace the sources' tags`(type: Type) = runTest {
        type.passes(listOf(listOf("Gore")), TagFilter(exclude = setOf("gore")), custom = listOf("Drama")) shouldBe true
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a series with no tags passes No tags shown`(type: Type) = runTest {
        type.passes(listOf(emptyList()), TagFilter(noTags = TriState.ENABLED_IS)) shouldBe true
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a tag aliased to a shown tag counts as it`(type: Type) = runTest {
        val aliases = TagAliases(listOf(TagAlias("Sci-fi", "Science Fiction")))
        type.passes(listOf(listOf("Sci-fi")), TagFilter(include = setOf("science fiction")), aliases = aliases) shouldBe
            true
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `tag search finds a series by an alias of its tag`(type: Type) = runTest {
        val aliases = TagAliases(listOf(TagAlias("Sci-fi", "Science Fiction")))
        val fields = libraryItemQueryFields(sourceKey = { "" }, tagAliases = aliases)
        libraryQueryMatches(QueryNode.from("tag:sci-fi"), type.row(listOf(listOf("Science Fiction"))), fields) shouldBe
            true
    }

    enum class Type {
        MANGA {
            override suspend fun row(members: List<List<String>>): LibraryItem {
                val items = members.mapIndexed { i, genre -> mangaRow(i.toLong(), genre) }
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
            override suspend fun row(members: List<List<String>>): LibraryItem {
                val novels = members.mapIndexed { i, genre ->
                    Novel.create().copy(id = i.toLong(), source = "plugin$i", genre = genre)
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

        abstract suspend fun row(members: List<List<String>>): LibraryItem

        suspend fun passes(
            members: List<List<String>>,
            tags: TagFilter,
            custom: List<String>? = null,
            aliases: TagAliases = TagAliases.NONE,
        ): Boolean {
            val fields = libraryItemFilterFields(
                sourceKey = { "" },
                adultSource = { false },
                lewdSourceName = { null },
                trackerIds = { emptyList() },
                customTags = { custom },
            )
            return libraryFilterMatches(row(members), tagPrefs(tags).copy(tagAliases = aliases), fields)
        }
    }

    private companion object {
        const val GROUP_ID = 7L
        val NO_BADGES = LibraryBadgePrefs(false, false, false, false, false)

        fun mangaRow(id: Long, genre: List<String>) = LibraryItem(
            libraryManga = LibraryManga(
                manga = Manga.create().copy(id = id, source = 42L + id, genre = genre),
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
            sourceName = "source",
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

        fun tagPrefs(tags: TagFilter) = LibraryFilterPrefs(
            downloaded = TriState.DISABLED,
            unread = TriState.DISABLED,
            started = TriState.DISABLED,
            bookmarked = TriState.DISABLED,
            completed = TriState.DISABLED,
            intervalCustom = TriState.DISABLED,
            lewd = TriState.DISABLED,
            includedTracks = emptySet(),
            excludedTracks = emptySet(),
            categoriesActive = false,
            categoriesInclude = emptySet(),
            categoriesExclude = emptySet(),
            tags = tags,
        )
    }
}
