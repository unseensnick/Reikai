package reikai.presentation.reader

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.runs
import io.mockk.unmockkConstructor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import reikai.domain.download.MangaChapterDownloadActions
import reikai.domain.manga.MangaPreferences
import reikai.domain.manga.MergedChapterProvider
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

/**
 * A real [ReaderViewModel] over an in-memory database and the real chapter interactors, so a mark or a
 * progress save lands where the next read finds it. Faked where the app leaves the process: the page
 * loader (the network), the download folders (answered from `onDisk`), trackers, and the merge group,
 * which is handed in as the provider would resolve it.
 *
 * [create] it, seed [manga] and [chapter] rows, then [open] a model and ask it things.
 */
class MangaReaderViewModelHarness private constructor(
    private val driver: JdbcSqliteDriver,
    database: Database,
) : AutoCloseable {

    companion object {
        suspend fun create(): MangaReaderViewModelHarness {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            return MangaReaderViewModelHarness(driver, DatabaseBindings.providesDatabase(driver))
        }
    }

    private val mangas = MangaRepositoryImpl(database)
    private val chapters = ChapterRepositoryImpl(database)
    private val titles = mutableMapOf<Long, String>()

    /** Stores a manga under a chosen [id], so a test can name its chapters' owners. */
    suspend fun manga(id: Long, source: Long, title: String): Manga {
        driver.execute(
            null,
            "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                "user_reader_flags, user_chapter_flags, state_cover_last_modified, user_favorite_at, " +
                "remote_update_strategy, state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                "($id, $source, '/manga/$id', '$title', 0, 1, 0, 0, 0, 1, 0, 0, '', '{}')",
            0,
        ).await()
        titles[id] = title
        return mangas.getMangaById(id)
    }

    /**
     * Stores a chapter under a chosen [id]. A source lists newest first and the reader pages by that
     * order reversed, so [order] defaults to one that reads in [number] order.
     */
    suspend fun chapter(
        id: Long,
        manga: Manga,
        number: Double,
        scanlator: String? = null,
        read: Boolean = false,
        order: Long = (1_000 - number * 10).toLong(),
    ): Chapter {
        val scanlatorValue = scanlator?.let { "'$it'" } ?: "NULL"
        driver.execute(
            null,
            "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, user_read, " +
                "user_bookmark, user_last_page_read, remote_chapter_number, remote_order, state_date_fetch, " +
                "remote_date_upload, remote_memo) VALUES ($id, ${manga.id}, '/$id', '$id', $scanlatorValue, " +
                "${if (read) 1 else 0}, 0, 0, $number, $order, 0, 0, '{}')",
            0,
        ).await()
        return stored(id)
    }

    /** [id] as the database holds it now. */
    suspend fun stored(id: Long): Chapter = chapters.getChapterById(id)!!

    /** A group of [manga] alone, as the provider resolves an unmerged series. */
    suspend fun single(manga: Manga): MergedChapterProvider.Group = MergedChapterProvider.Group(
        mangaById = mapOf(manga.id to manga),
        chapters = chapters.getChapterByMangaId(manga.id),
        sourceNameByMangaId = mapOf(manga.id to manga.title),
    )

    /**
     * Opens [chapterId] of [manga], waits for the first chapter to land, then asks [probe]. [preferences]
     * seeds the store the reader, download and library settings read, by key.
     */
    suspend fun <T> open(
        manga: Manga,
        chapterId: Long,
        group: MergedChapterProvider.Group? = null,
        preferences: Map<String, Any> = emptyMap(),
        onDisk: Set<Long> = emptySet(),
        downloadedOnly: Boolean = false,
        sourceScoped: Boolean = false,
        onDownload: (List<Chapter>) -> Unit = {},
        onDelete: (List<Chapter>) -> Unit = {},
        probe: suspend (ReaderViewModel, ReaderViewModel.State) -> T,
    ): T {
        val resolved = group ?: single(manga)
        // The model starts loading from its init block, which a main dispatcher nothing advances never runs.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkConstructor(ChapterLoader::class)
        coEvery { anyConstructed<ChapterLoader>().loadChapter(any(), any()) } just runs
        val store = ViewModelStore()
        try {
            val isOnDisk = { name: String, title: String ->
                resolved.pooledChapters.any { it.name == name && titles[it.mangaId] == title && it.id in onDisk }
            }
            val downloadManager = mockk<DownloadManager>(relaxed = true) {
                every { isChapterDownloaded(any(), any(), any(), any(), any()) } answers {
                    isOnDisk(firstArg(), arg(3))
                }
                coEvery { downloadChapters(any(), any(), any()) } answers { onDownload(secondArg()) }
                every { deleteChapters(any(), any(), any()) } answers { onDelete(firstArg()) }
            }
            val getManga = GetManga(mangas)
            val sourceManager = mockk<SourceManager>(relaxed = true)
            val prefs = InMemoryPreferenceStore(
                preferences.asSequence().map { (key, value) ->
                    InMemoryPreferenceStore.InMemoryPreference(key, value, value)
                },
            )
            val downloadPreferences = DownloadPreferences(prefs)
            val model = ReaderViewModel(
                savedState = SavedStateHandle(
                    mapOf("manga" to manga.id, "chapter" to chapterId, "source_scoped" to sourceScoped),
                ),
                context = mockk<Context>(relaxed = true),
                sourceManager = sourceManager,
                downloadManager = downloadManager,
                downloadProvider = mockk(relaxed = true),
                imageSaver = mockk(relaxed = true),
                readerPreferences = ReaderPreferences(prefs),
                basePreferences = mockk<BasePreferences>().also { base ->
                    every { base.downloadedOnly } returns mockk<Preference<Boolean>> {
                        every { get() } returns downloadedOnly
                    }
                },
                downloadPreferences = downloadPreferences,
                trackPreferences = TrackPreferences(prefs),
                trackChapter = mockk(relaxed = true),
                sourceTracker = mockk(relaxed = true),
                getManga = getManga,
                getCustomMangaInfo = mockk { every { subscribe(any()) } returns flowOf(null) },
                getChaptersByMangaId = GetChaptersByMangaId(chapters),
                getNextChapters = mockk(relaxed = true),
                upsertHistory = mockk(relaxed = true),
                updateChapter = UpdateChapter(chapters),
                setMangaViewerFlags = mockk(relaxed = true),
                getIncognitoState = mockk(relaxed = true),
                libraryPreferences = LibraryPreferences(prefs),
                coverManager = mockk(relaxed = true),
                updateManga = mockk(relaxed = true),
                coverCache = mockk(relaxed = true),
                chapterCache = mockk(relaxed = true),
                downloadCache = mockk(relaxed = true) {
                    every { isChapterDownloaded(any(), any(), any(), any(), any()) } answers {
                        isOnDisk(firstArg(), arg(3))
                    }
                },
                mergedChapterProvider = mockk { coEvery { load(any()) } returns resolved },
                mangaPreferences = MangaPreferences(prefs),
                // Download removal and the source's own tracker leave the process.
                setReadStatus = SetReadStatus(
                    downloadPreferences,
                    mockk(relaxed = true),
                    mangas,
                    chapters,
                    mockk(relaxed = true),
                ),
                getChapter = GetChapter(chapters),
                chapterDownloadActions = MangaChapterDownloadActions(downloadManager, getManga, sourceManager),
            ).also { store.put("reader", it) }
            val state = settled(model) { it.viewerChapters != null || it.initError != null }
                .also { it.initError?.let { error -> throw error } }
            return probe(model, state)
        } finally {
            store.clear()
            unmockkConstructor(ChapterLoader::class)
        }
    }

    override fun close() = driver.close()
}

/** The model loads on the IO dispatcher, which virtual time does not reach, so this waits in real time. */
suspend fun settled(
    model: ReaderViewModel,
    until: (ReaderViewModel.State) -> Boolean,
): ReaderViewModel.State = withContext(Dispatchers.Default) {
    withTimeout(10_000) { model.state.first(until) }
}

/** [chapter] as loaded with [count] pages, which the stubbed page loader never does itself. */
fun loadedPages(chapter: ReaderChapter, count: Int): List<ReaderPage> =
    List(count) {
        ReaderPage(it)
    }.onEach { it.chapter = chapter }.also { chapter.state = ReaderChapter.State.Loaded(it) }
