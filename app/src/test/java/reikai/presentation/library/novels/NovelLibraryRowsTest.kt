package reikai.presentation.library.novels

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.merge.MergedChapterUnitRepository
import reikai.domain.novel.LnSourceIdentity
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.Novel
import reikai.novel.download.NovelDownloadCache
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.library.SourceBadge
import tachiyomi.domain.library.service.LibraryPreferences

/** The novel rows never wait on the plugins: a badge draws from the sources registered so far. */
class NovelLibraryRowsTest {

    private val registered = MutableStateFlow<List<NovelSource>>(emptyList())
    private val firstLoadDone = MutableStateFlow(false)

    @Test
    fun `a novel's row draws before the plugins have loaded, without calling its source missing`() = runTest {
        val rows = rows(backgroundScope).stateIn(backgroundScope, SharingStarted.Eagerly, null)

        rows.filterNotNull().first().items.single().badges.source shouldBe SourceBadge.Generic
    }

    @Test
    fun `a source still unregistered once the first load finishes is missing`() = runTest {
        val rows = rows(backgroundScope).stateIn(backgroundScope, SharingStarted.Eagerly, null)
        rows.filterNotNull().first()

        firstLoadDone.value = true

        rows.filterNotNull().first { it.items.single().badges.source != SourceBadge.Generic }
            .items.single().badges.source shouldBe SourceBadge.Missing
    }

    @Test
    fun `a novel's badge redraws once its plugin registers`() = runTest {
        val rows = rows(backgroundScope).stateIn(backgroundScope, SharingStarted.Eagerly, null)
        rows.filterNotNull().first()

        registered.value = listOf(plugin)

        rows.filterNotNull().first { it.items.single().badges.source != SourceBadge.Generic }
            .items.single().badges.source shouldBe SourceBadge.Icon(ICON)
    }

    @Test
    fun `a plugin the library does not hold registering rebuilds no row`() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rowsClass(backgroundScope).buildRows().collect {}
        }

        registered.value = listOf(otherPlugin)
        advanceUntilIdle()

        coVerify(exactly = 1) { sourceManager.identityOf(SOURCE) }
    }

    @Test
    fun `the library's own plugin registering restamps the rows without collapsing again`() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rowsClass(backgroundScope).buildRows().collect {}
        }

        registered.value = listOf(plugin)
        advanceUntilIdle()

        verify(exactly = 1) { downloadCache.getDownloadCount(any()) }
    }

    private val plugin = mockk<NovelSource> {
        every { id } returns SOURCE
        every { iconUrl } returns ICON
        every { site } returns ""
    }

    private val otherPlugin = mockk<NovelSource> {
        every { id } returns "plugin.other"
        every { iconUrl } returns "https://plugin.other/icon.png"
        every { site } returns ""
    }

    private val downloadCache = mockk<NovelDownloadCache> {
        every { changes } returns MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
        every { getDownloadCount(any()) } returns 0
    }

    private val sourceManager = mockk<NovelSourceManager> {
        every { sources } returns registered
        every { firstLoadDone } returns this@NovelLibraryRowsTest.firstLoadDone
        // The first plugin load, which a cold start with many plugins can take seconds over.
        coEvery { getAll() } coAnswers { awaitCancellation() }
        coEvery { this@mockk.get(any<String>()) } coAnswers { awaitCancellation() }
        coEvery { identityOf(any()) } returns LnSourceIdentity(name = "Plugin")
    }

    private fun rows(scope: CoroutineScope) = rowsClass(scope).rows

    private fun rowsClass(scope: CoroutineScope) = NovelLibraryRows(
        novelRepository = mockk<NovelRepository> {
            every { getLibraryNovelAsFlow() } returns flowOf(listOf(libraryNovel))
        },
        novelDownloadCache = downloadCache,
        libraryPreferences = libraryPreferences,
        reikaiLibraryPreferences = reikaiLibraryPreferences,
        sourceManager = sourceManager,
        mergeGroupRepository = mockk<MergeGroupRepository> {
            every { getAllMembershipsAsFlow(any()) } returns flowOf(emptyMap())
            every { getOverrideRankingsAsFlow(any()) } returns flowOf(emptyMap())
        },
        mergedChapterUnitRepository = mockk<MergedChapterUnitRepository> {
            every { getGroupCountsAsFlow(any()) } returns flowOf(emptyMap())
            every { getStitchWritesAsFlow() } returns flowOf(Unit)
            every { getRecognizedChapterCountsAsFlow() } returns flowOf(emptyMap())
        },
        getNextNovelChapter = mockk(),
        basePreferences = mockk(),
        localLanguages = mockk { every { subscribeAll() } returns flowOf(emptyMap()) },
        scope = scope,
    )

    private val libraryNovel = LibraryNovel(
        novel = Novel.create().copy(id = 1L, source = SOURCE),
        categories = emptyList(),
        totalChapters = 0,
        readCount = 0,
        bookmarkCount = 0,
        downloadCount = 0,
        latestUpload = null,
        chapterFetchedAt = null,
        lastRead = null,
    )

    // An in-memory preference's changes() never emits, and the row build waits for every input.
    private val libraryPreferences = mockk<LibraryPreferences> {
        every { downloadBadge.changes() } returns flowOf(true)
        every { unreadBadge.changes() } returns flowOf(true)
        every { localBadge.changes() } returns flowOf(true)
        every { languageBadge.changes() } returns flowOf(true)
    }

    private val reikaiLibraryPreferences = mockk<ReikaiLibraryPreferences> {
        every { sourceBadge.changes() } returns flowOf(true)
        every { seriesMergingEnabled.changes() } returns flowOf(false)
        every { showMergeSourceIcons.changes() } returns flowOf(false)
        every { preferredNovelSources.changes() } returns flowOf(emptyList())
    }

    private companion object {
        const val SOURCE = "plugin.x"
        const val ICON = "https://plugin.x/icon.png"
    }
}
