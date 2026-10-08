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
import reikai.presentation.library.novels.toLibraryItem
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

/**
 * The library's Lewd filter counts an extension marked 18+ as adult on manga and novels alike, but not one
 * marked Mixed, which covers most mainstream extensions; an adult genre tag counts whatever the warning.
 */
class LibraryLewdConformanceTest {

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `an untagged entry from an 18+ extension is left out by Lewd exclude`(type: Type) = runTest {
        type.passesLewdExclude(ContentWarning.NSFW, UNTAGGED) shouldBe false
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `an untagged entry from a Mixed extension passes Lewd exclude`(type: Type) = runTest {
        type.passesLewdExclude(ContentWarning.MIXED, UNTAGGED) shouldBe true
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `an adult-tagged entry from a Mixed extension is left out by Lewd exclude`(type: Type) = runTest {
        type.passesLewdExclude(ContentWarning.MIXED, ADULT_TAGGED) shouldBe false
    }

    // A scan that never finishes must not hang the library: past the wait, tags and names decide alone.
    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a stalled extension scan answers within the wait`(type: Type) = runTest {
        type.passesLewdExclude(ContentWarning.NSFW, ADULT_TAGGED, stalled = true)

        currentTime shouldBeLessThanOrEqual SCAN_WAIT_MS
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a stalled extension scan still leaves out an adult-tagged entry`(type: Type) = runTest {
        type.passesLewdExclude(ContentWarning.NSFW, ADULT_TAGGED, stalled = true) shouldBe false
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a stalled extension scan counts no source as adult`(type: Type) = runTest {
        type.passesLewdExclude(ContentWarning.NSFW, UNTAGGED, stalled = true) shouldBe true
    }

    /** Each type resolves its adult-source set the way its library model does, then runs the shared filter. */
    enum class Type {
        MANGA {
            override suspend fun passesLewdExclude(
                warning: ContentWarning,
                genre: List<String>,
                stalled: Boolean,
            ): Boolean {
                val extSource = mockk<Source> { every { id } returns SOURCE_ID }
                val extension = mockk<Extension.Loaded> {
                    every { contentWarning } returns warning
                    every { sources } returns listOf(extSource)
                }
                val extensionManager = mockk<ExtensionManager> {
                    every { loadedExtensionsFlow } returns
                        if (stalled) MutableSharedFlow() else MutableStateFlow(listOf(extension))
                }
                val sourceManager = mockk<SourceManager>()
                coEvery { sourceManager.get(any()) } returns mockk<Source> { every { name } returns SOURCE_NAME }
                val adultSources = AdultContentChecker(extensionManager, sourceManager, mockk())
                    .libraryAdultMangaSources(setOf(SOURCE_ID))
                val row = LibraryItem(
                    libraryManga = LibraryManga(
                        manga = Manga.create().copy(id = 1L, source = SOURCE_ID, genre = genre),
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
                    badges = LibraryItem.Badges(
                        downloadCount = 0,
                        unreadCount = 0,
                        isLocal = false,
                        sourceLanguage = "",
                    ),
                )
                val fields = libraryItemFilterFields(
                    adultSource = { it.libraryManga.manga.source in adultSources },
                    lewdSourceName = { SOURCE_NAME },
                    trackerIds = { emptyList() },
                )
                return libraryFilterMatches(row, excludeLewd, fields)
            }
        },
        NOVEL {
            override suspend fun passesLewdExclude(
                warning: ContentWarning,
                genre: List<String>,
                stalled: Boolean,
            ): Boolean {
                val source = mockk<NovelSource> { every { contentWarning } returns warning }
                val novelSources = mockk<NovelSourceManager> {
                    if (stalled) {
                        coEvery { getWithoutPlugins(NOVEL_SOURCE) } coAnswers { awaitCancellation() }
                    } else {
                        coEvery { getWithoutPlugins(NOVEL_SOURCE) } returns source
                    }
                }
                val adultSources = AdultContentChecker(mockk(), mockk(), novelSources)
                    .libraryAdultNovelSources(setOf(NOVEL_SOURCE))
                val novel = Novel.create().copy(id = 1L, source = NOVEL_SOURCE, genre = genre)
                val row = LibraryNovel(
                    novel = novel,
                    categories = emptyList(),
                    totalChapters = 0,
                    readCount = 0,
                    bookmarkCount = 0,
                    downloadCount = 0,
                    latestUpload = 0,
                    chapterFetchedAt = 0,
                    lastRead = 0,
                ).toLibraryItem(
                    badgePrefs = LibraryBadgePrefs(false, false, false, false, false),
                    sourceLanguage = "en",
                    sourceIcon = SourceBadge.Generic,
                    sourceName = SOURCE_NAME,
                )
                val fields = libraryItemFilterFields(
                    adultSource = { novel.source in adultSources },
                    lewdSourceName = { null },
                    trackerIds = { emptyList() },
                )
                return libraryFilterMatches(row, excludeLewd, fields)
            }
        },
        ;

        abstract suspend fun passesLewdExclude(
            warning: ContentWarning,
            genre: List<String>,
            stalled: Boolean = false,
        ): Boolean
    }

    private companion object {
        const val SOURCE_ID = 42L
        const val NOVEL_SOURCE = "tachiyomi:42"
        const val SOURCE_NAME = "Some Reader"
        val UNTAGGED = listOf("Action")
        val ADULT_TAGGED = listOf("Action", "Smut")
        const val SCAN_WAIT_MS = 5_000L

        val excludeLewd = LibraryFilterPrefs(
            downloaded = TriState.DISABLED,
            unread = TriState.DISABLED,
            started = TriState.DISABLED,
            bookmarked = TriState.DISABLED,
            completed = TriState.DISABLED,
            intervalCustom = TriState.DISABLED,
            lewd = TriState.ENABLED_NOT,
            includedTracks = emptySet(),
            excludedTracks = emptySet(),
            categoriesActive = false,
            categoriesInclude = emptySet(),
            categoriesExclude = emptySet(),
        )
    }
}
