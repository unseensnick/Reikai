package reikai.presentation.library

import eu.kanade.tachiyomi.data.download.DownloadManager
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.merge.MergedChapterUnitRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.local.LocalNovelLanguageRepository
import reikai.domain.novel.model.LibraryNovel
import reikai.novel.download.NovelDownloadCache
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.library.novels.NovelLibraryRows
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga

/**
 * Each type's library rows are one build however many screens read them: the library, Statistics and
 * Hidden entries open together subscribe to the library once and build once. Over the real row classes,
 * with an empty library; the sharing policy itself is pinned by SharedLibraryRowsTest.
 */
class LibraryRowsSharingConformanceTest {

    private val librarySubscriptions = MutableStateFlow(0)
    private val builds = MutableStateFlow(0)
    private val cacheChanges = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }

    @ParameterizedTest
    @EnumSource(names = ["MANGA", "NOVELS"])
    fun `two readers at once subscribe to the library once and build once`(type: ContentType) = runTest {
        val rows = rowsOf(type, backgroundScope)
        val library = rows.stateIn(backgroundScope, SharingStarted.Eagerly, null)
        val stats = rows.stateIn(backgroundScope, SharingStarted.Eagerly, null)

        library.filterNotNull().first()
        stats.filterNotNull().first()

        (librarySubscriptions.value to builds.value) shouldBe (1 to 1)
    }

    private fun <T> Flow<T>.counted(): Flow<T> = onStart { librarySubscriptions.update { it + 1 } }

    private fun rowsOf(type: ContentType, scope: CoroutineScope): Flow<*> = when (type) {
        ContentType.MANGA -> MangaLibraryRows(
            getLibraryManga = mockk<GetLibraryManga> {
                every { subscribe() } returns
                    flowOf(emptyList<LibraryManga>()).counted()
            },
            libraryPreferences = libraryPreferences,
            reikaiLibraryPreferences = reikaiLibraryPreferences,
            sourceManager = mockk(),
            downloadManager = mockk<DownloadManager> {
                every { getDownloadCounts(any()) } answers {
                    builds.update { it + 1 }
                    emptyMap()
                }
            },
            downloadCache = mockk { every { changes } returns cacheChanges },
            mergeGroupRepository = mergeGroupRepository,
            mergedChapterUnitRepository = mergedChapterUnitRepository,
            mergedChapterProvider = mockk(),
            mangaPreferences = mockk(),
            basePreferences = mockk(),
            sourcePreferences = mockk(),
            scope = scope,
        ).rows
        else -> NovelLibraryRows(
            novelRepository = mockk<NovelRepository> {
                every { getLibraryNovelAsFlow() } returns flowOf(emptyList<LibraryNovel>()).counted()
            },
            novelDownloadCache = mockk<NovelDownloadCache> { every { changes } returns cacheChanges },
            libraryPreferences = libraryPreferences,
            reikaiLibraryPreferences = reikaiLibraryPreferences,
            sourceManager = mockk<NovelSourceManager> {
                // Each build reads every registered source's icon once, which is what counts it.
                every { sources } returns flowOf(
                    listOf(
                        mockk<NovelSource> {
                            every { iconUrl } answers {
                                builds.update { it + 1 }
                                null
                            }
                            every { id } returns "plugin"
                        },
                    ),
                )
                every { firstLoadDone } returns flowOf(true)
            },
            mergeGroupRepository = mergeGroupRepository,
            mergedChapterUnitRepository = mergedChapterUnitRepository,
            getNextNovelChapter = mockk(),
            basePreferences = mockk(),
            localLanguages = mockk<LocalNovelLanguageRepository> {
                every { subscribeAll() } returns flowOf(emptyMap())
            },
            scope = scope,
        ).rows
    }

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
        every { preferredMangaSources.changes() } returns flowOf(emptyList())
        every { preferredNovelSources.changes() } returns flowOf(emptyList())
    }

    private val mergeGroupRepository = mockk<MergeGroupRepository> {
        every { getAllMembershipsAsFlow(any()) } returns flowOf(emptyMap())
        every { getOverrideRankingsAsFlow(any()) } returns flowOf(emptyMap())
    }

    private val mergedChapterUnitRepository = mockk<MergedChapterUnitRepository> {
        every { getGroupCountsAsFlow(any()) } returns flowOf(emptyMap())
        every { getStitchWritesAsFlow() } returns flowOf(Unit)
        every { getRecognizedChapterCountsAsFlow() } returns flowOf(emptyMap())
    }
}
