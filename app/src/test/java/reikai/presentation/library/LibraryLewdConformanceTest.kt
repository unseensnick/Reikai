package reikai.presentation.library

import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.model.ContentWarning
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.manga.AdultContentChecker
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.Novel
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.library.novels.NovelMergeCollapse
import reikai.presentation.library.novels.toLibraryRow
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

/**
 * The library's Lewd filter counts an extension marked 18+ as adult on manga and novels alike, but not one
 * marked Mixed, which covers most mainstream extensions; an adult genre tag counts whatever the warning.
 * A merged series is adult when any of its members is, whichever member the card shows.
 */
class LibraryLewdConformanceTest {

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `an untagged entry from an 18+ extension is left out by Lewd exclude`(type: Type) = runTest {
        type.passes(listOf(Member(ContentWarning.NSFW, UNTAGGED)), TriState.ENABLED_NOT) shouldBe false
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `an untagged entry from a Mixed extension passes Lewd exclude`(type: Type) = runTest {
        type.passes(listOf(Member(ContentWarning.MIXED, UNTAGGED)), TriState.ENABLED_NOT) shouldBe true
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `an adult-tagged entry from a Mixed extension is left out by Lewd exclude`(type: Type) = runTest {
        type.passes(listOf(Member(ContentWarning.MIXED, ADULT_TAGGED)), TriState.ENABLED_NOT) shouldBe false
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a merged series with an adult-tagged hidden member is left out by Lewd exclude`(type: Type) = runTest {
        type.passes(listOf(CLEAN, Member(ContentWarning.MIXED, ADULT_TAGGED)), TriState.ENABLED_NOT) shouldBe false
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a merged series with an adult-tagged hidden member is kept by Lewd include`(type: Type) = runTest {
        type.passes(listOf(CLEAN, Member(ContentWarning.MIXED, ADULT_TAGGED)), TriState.ENABLED_IS) shouldBe true
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a merged series with a hidden member from an 18+ extension is left out by Lewd exclude`(type: Type) =
        runTest {
            type.passes(listOf(CLEAN, Member(ContentWarning.NSFW, UNTAGGED)), TriState.ENABLED_NOT) shouldBe false
        }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a merged series with a hidden member from an 18+ extension is kept by Lewd include`(type: Type) =
        runTest {
            type.passes(listOf(CLEAN, Member(ContentWarning.NSFW, UNTAGGED)), TriState.ENABLED_IS) shouldBe true
        }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a merged series with no adult member passes Lewd exclude`(type: Type) = runTest {
        type.passes(listOf(CLEAN, CLEAN), TriState.ENABLED_NOT) shouldBe true
    }

    // A scan that never finishes must not hang the library: past the wait, tags and names decide alone.
    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a stalled extension scan answers within the wait`(type: Type) = runTest {
        type.passes(listOf(Member(ContentWarning.NSFW, ADULT_TAGGED)), TriState.ENABLED_NOT, stalled = true)

        currentTime shouldBeLessThanOrEqual SCAN_WAIT_MS
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a stalled extension scan still leaves out an adult-tagged entry`(type: Type) = runTest {
        val members = listOf(Member(ContentWarning.NSFW, ADULT_TAGGED))

        type.passes(members, TriState.ENABLED_NOT, stalled = true) shouldBe false
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a stalled extension scan counts no source as adult`(type: Type) = runTest {
        type.passes(listOf(Member(ContentWarning.NSFW, UNTAGGED)), TriState.ENABLED_NOT, stalled = true) shouldBe true
    }

    /** One source of a series, each from its own extension; the first member is the one the card shows. */
    data class Member(val warning: ContentWarning, val genre: List<String>)

    /**
     * Each type builds its rows through its own merge collapse, resolves its adult-source set the way its
     * library model does, then runs the shared filter.
     */
    enum class Type {
        MANGA {
            override suspend fun passes(members: List<Member>, lewd: TriState, stalled: Boolean): Boolean {
                val extensions = members.mapIndexed { i, member ->
                    val extSource = mockk<Source> { every { id } returns SOURCE_ID + i }
                    mockk<Extension.Loaded> {
                        every { contentWarning } returns member.warning
                        every { sources } returns listOf(extSource)
                    }
                }
                val extensionManager = mockk<ExtensionManager> {
                    every { loadedExtensionsFlow } returns
                        if (stalled) MutableSharedFlow() else MutableStateFlow(extensions)
                }
                val sourceManager = mockk<SourceManager>()
                coEvery { sourceManager.get(any()) } returns mockk<Source> { every { name } returns SOURCE_NAME }
                val items = members.mapIndexed { i, member -> mangaRow(i.toLong(), SOURCE_ID + i, member.genre) }
                val ids = items.map { it.id }
                val row = MangaMergeCollapse.collapse(
                    items = items,
                    membership = ids.associateWith { GROUP_ID },
                    mergingEnabled = true,
                    showMergeSourceIcons = false,
                    resolveSource = { mockk() },
                    badgePrefs = NO_BADGES,
                    overrideRankings = mapOf(GROUP_ID to ids),
                ).single()
                val sourceKey = { item: LibraryItem -> item.libraryManga.manga.source.toString() }
                val lookupIds = adultLookupKeys(listOf(row), sourceKey).mapTo(mutableSetOf()) { it.toLong() }
                val adultSources = AdultContentChecker(extensionManager, sourceManager, mockk())
                    .libraryAdultMangaSources(lookupIds)
                    .mapTo(mutableSetOf()) { it.toString() }
                val fields = libraryItemFilterFields(
                    sourceKey = sourceKey,
                    adultSource = { it in adultSources },
                    lewdSourceName = { it.name },
                    trackerIds = { emptyList() },
                )
                return libraryFilterMatches(row, lewdPrefs(lewd), fields)
            }
        },
        NOVEL {
            override suspend fun passes(members: List<Member>, lewd: TriState, stalled: Boolean): Boolean {
                val novels = members.mapIndexed { i, member ->
                    Novel.create().copy(id = i.toLong(), source = "$NOVEL_SOURCE$i", genre = member.genre)
                }
                val novelSources = mockk<NovelSourceManager>()
                novels.zip(members) { novel, member ->
                    val source = mockk<NovelSource> { every { contentWarning } returns member.warning }
                    if (stalled) {
                        coEvery { novelSources.getWithoutPlugins(novel.source) } coAnswers { awaitCancellation() }
                    } else {
                        coEvery { novelSources.getWithoutPlugins(novel.source) } returns source
                    }
                }
                val ids = novels.map { it.id }
                val row = NovelMergeCollapse.collapse(
                    library = novels.map(::libraryNovel),
                    membership = ids.associateWith { GROUP_ID },
                    mergingEnabled = true,
                    overrideRankings = mapOf(GROUP_ID to ids),
                ).single().toLibraryRow(
                    badgePrefs = NO_BADGES,
                    showSourceIcons = false,
                    querySource = { LibraryQuerySource(it, SOURCE_NAME.lowercase(), "en", isLocal = false) },
                    sourceBadge = { SourceBadge.Generic },
                )
                val sourceById = novels.associate { it.id to it.source }
                val sourceKey = { item: LibraryItem -> sourceById[item.id].orEmpty() }
                val adultSources = AdultContentChecker(mockk(), mockk(), novelSources)
                    .libraryAdultNovelSources(adultLookupKeys(listOf(row), sourceKey))
                val fields = libraryItemFilterFields(
                    sourceKey = sourceKey,
                    adultSource = { it in adultSources },
                    lewdSourceName = { null },
                    trackerIds = { emptyList() },
                )
                return libraryFilterMatches(row, lewdPrefs(lewd), fields)
            }
        },
        ;

        abstract suspend fun passes(members: List<Member>, lewd: TriState, stalled: Boolean = false): Boolean
    }

    private companion object {
        const val SOURCE_ID = 42L
        const val NOVEL_SOURCE = "tachiyomi:4"
        const val SOURCE_NAME = "Some Reader"
        const val GROUP_ID = 7L
        val UNTAGGED = listOf("Action")
        val ADULT_TAGGED = listOf("Action", "Smut")
        val CLEAN = Member(ContentWarning.MIXED, UNTAGGED)
        const val SCAN_WAIT_MS = 5_000L
        val NO_BADGES = LibraryBadgePrefs(false, false, false, false, false)

        fun mangaRow(id: Long, source: Long, genre: List<String>) = LibraryItem(
            libraryManga = LibraryManga(
                manga = Manga.create().copy(id = id, source = source, genre = genre),
                categories = emptyList(),
                totalChapters = 0,
                readCount = 0,
                bookmarkCount = 0,
                latestUpload = 0,
                chapterFetchedAt = 0,
                lastRead = 0,
            ),
            downloadCount = 0,
            unreadCount = 0,
            isLocal = false,
            badges = LibraryItem.Badges(downloadCount = 0, unreadCount = 0, isLocal = false, sourceLanguage = ""),
            sourceName = SOURCE_NAME.lowercase(),
        )

        fun libraryNovel(novel: Novel) = LibraryNovel(
            novel = novel,
            categories = emptyList(),
            totalChapters = 0,
            readCount = 0,
            bookmarkCount = 0,
            downloadCount = 0,
            latestUpload = 0,
            chapterFetchedAt = 0,
            lastRead = 0,
        )

        fun lewdPrefs(lewd: TriState) = LibraryFilterPrefs(
            downloaded = TriState.DISABLED,
            unread = TriState.DISABLED,
            started = TriState.DISABLED,
            bookmarked = TriState.DISABLED,
            completed = TriState.DISABLED,
            intervalCustom = TriState.DISABLED,
            lewd = lewd,
            includedTracks = emptySet(),
            excludedTracks = emptySet(),
            categoriesActive = false,
            categoriesInclude = emptySet(),
            categoriesExclude = emptySet(),
        )
    }
}
