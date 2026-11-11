package reikai.data.local

import android.content.Context
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.mangaRestorer
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import mihon.domain.source.interactor.UpdateMangaFromRemote
import nl.adaptivity.xmlutil.serialization.XML
import reikai.data.chapter.ChapterNumberOverrideRepositoryImpl
import reikai.data.chapter.ChapterTombstoneRepositoryImpl
import reikai.data.chapter.LocalChapterTombstonesImpl
import reikai.data.dedupe.DownloadFolderMerge
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelChapterSyncRules
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.chapter.NoChapterNumberOverrides
import reikai.domain.chapter.NoLocalVolumes
import reikai.domain.download.DownloadIndexRules
import reikai.domain.library.ContentType
import reikai.domain.library.FakeLibraryManualOrderRepository
import reikai.domain.local.LocalSeriesLocks
import reikai.domain.manga.RemoveMangaFromLibrary
import reikai.domain.readinglog.NoReadingLog
import reikai.presentation.browse.AddOutcome
import reikai.presentation.browse.MangaLibraryAdder
import reikai.storage.FakeSafProvider
import reikai.storage.FakeSafProvider.ChildListingFault
import reikai.storage.PARKED_PREFIX
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.File
import kotlin.random.Random

/** The process dying: an Error, so no `catch (e: Exception)` takes it for a failure to handle. */
class KillError : Error("killed")

/** What went wrong in a run of [LocalLibraryWorld], and the steps that led there. */
class WorldFailure(val step: Int, val message: String, val trace: List<String>)

/**
 * A local manga library and a downloads folder on the fake picked-folder provider with a real database, driven
 * by random steps (in-app operations, edits made outside the app, storage faults, kills) and checked after each
 * against what a user must never lose: a file, or a chapter's reading state. Step i draws from its own generator,
 * so a run with some steps left out still makes the same choices in the others, which is what shrinking uses.
 * Novels are not modelled: their listing opens the book through the archive library, which the JVM has not got.
 */
class LocalLibraryWorld(private val context: Context, private val root: File, private val seed: Long) {

    data class St(val read: Boolean, val bookmark: Boolean, val page: Int)

    private class Ch(val id: Int, val tokens: List<String>) {
        var state: St? = null
        var uncertain = false
        var removed = false

        /** Where the chapter's folder stood when it was deleted outside the app, until it is put back. */
        var absent: List<String>? = null

        /** Where it stood when Delete volumes took it, whole and unhindered, so it comes back unread there. */
        var deletedAt: List<String>? = null
    }

    private val local = File(root, "Reikai/local").apply { mkdirs() }
    private val downloads = File(root, "Reikai/downloads").apply { mkdirs() }
    private val real = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val faults = Faults()
    private val driver = KillableDriver(real) { faults.tick() }
    private lateinit var database: Database
    private lateinit var app: App

    private val chapters = mutableListOf<Ch>()
    private val byToken = HashMap<String, Ch>()

    /** Tokens of files that are no chapter page (imported pictures, download pages) and must stay on disk. */
    private val loose = LinkedHashSet<String>()
    private val pending = mutableListOf<Pair<LocalVolumeMove, List<String>>>()

    /** Series folders renamed outside the app, by the address their rows are still at until they are linked. */
    private val renamedOutside = HashMap<String, String>()
    private val trace = mutableListOf<String>()

    /** What the run did so far, a line per step, operation, fault and result. */
    val log: List<String> get() = trace
    private val violations = mutableListOf<String>()
    private var counter = 0
    private var rng = Random(seed)

    private inner class Faults {
        var on = false
        var dead = false
        var rate = 0.0
        var killAt = -1
        var calls = 0
        val sticky = HashMap<File, Pair<ChildListingFault, Int>>()

        /** What the provider leaves out of every listing, each by the first token under it. */
        val hidden = HashSet<String>()

        private fun idOf(file: File): String? = file.walkTopDown().filter {
            it.isFile
        }.mapNotNull(::tokenOf).minOrNull()

        fun tick() {
            if (dead) throw KillError()
            if (!on) return
            calls++
            if (calls == killAt) {
                dead = true
                trace += "    KILL at call $calls"
                throw KillError()
            }
        }

        fun onCall(call: String, target: File) {
            tick()
            if (!on) return
            val roll = rng.nextDouble()
            when (call) {
                "queryChildDocuments" -> Unit
                "queryDocument" -> if (roll < rate * 0.5) fail(call, target)
                "renameDocument" -> when {
                    roll < rate -> fail(call, target)
                    roll < rate * 3 && !target.exists() -> {
                        trace += "    squatter takes ${rel(target)}"
                        target.mkdir()
                    }
                }
                else -> if (roll < rate * 0.4) fail(call, target)
            }
        }

        private fun fail(call: String, target: File): Nothing {
            trace += "    $call fails on ${rel(target)}"
            error("$call failed")
        }

        fun listing(parent: File): ChildListingFault? {
            if (!on) return null
            sticky[parent]?.let { (fault, left) ->
                if (left <= 1) sticky.remove(parent) else sticky[parent] = fault to left - 1
                return fault
            }
            // A file the provider hides is hidden at every reading, wherever a rename has taken its folder since.
            parent.listFiles().orEmpty().sortedBy { it.name }.firstOrNull { idOf(it) in hidden }
                ?.let { return ChildListingFault.Without(it.name) }
            if (rng.nextDouble() >= rate) return null
            val fault = when (rng.nextInt(7)) {
                0 -> ChildListingFault.Throw
                1 -> ChildListingFault.NullCursor
                2 -> ChildListingFault.Loading
                3 -> ChildListingFault.Short(rng.nextInt(3))
                4 -> ChildListingFault.Short(rng.nextInt(3)).also { sticky[parent] = it to 1 + rng.nextInt(3) }
                else -> {
                    val child = parent.listFiles().orEmpty().sortedBy { it.name }.randomOrNull(rng) ?: return null
                    hidden += idOf(child) ?: return null
                    ChildListingFault.Without(child.name)
                }
            }
            trace += "    listing of ${rel(parent)}: $fault${if (parent in sticky) " (repeated)" else ""}"
            return fault
        }
    }

    private class KillableDriver(private val real: SqlDriver, private val tick: () -> Unit) : SqlDriver by real {
        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            tick()
            return real.execute(identifier, sql, parameters, binders)
        }

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            tick()
            return real.executeQuery(identifier, sql, mapper, parameters, binders)
        }

        override fun newTransaction(): QueryResult<Transacter.Transaction> {
            tick()
            return real.newTransaction()
        }
    }

    /** Everything a process builds at start, over the database and the disk that outlive it. */
    private inner class App {
        val locks = LocalSeriesLocks()
        val scope = CoroutineScope(
            Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e ->
                if (e !is KillError) violations += "an app-scope job threw $e"
            },
        )
        val storage = mockk<StorageManager> { every { getLocalSourceDirectory() } answers { picked(local) } }
        val fileSystem = LocalSourceFileSystem(storage)
        val mangaRepository = MangaRepositoryImpl(database)
        val chapterRepository = ChapterRepositoryImpl(database)
        val tombstoneRepository = ChapterTombstoneRepositoryImpl(database)
        val journal = LocalFileJournalRepositoryImpl(database)
        val chapterMoves = LocalChapterMoveRepositoryImpl(database, tombstoneRepository)
        private val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
        private val numberOverrides = ChapterNumberOverrideRepositoryImpl(database)
        private val copyMarks = InMemoryChapterCopyMarks()
        private val tombstones =
            LocalChapterTombstonesImpl(tombstoneRepository, LocalChapterFiles(context, fileSystem, storage))
        val source = LocalSource(
            context,
            fileSystem,
            LocalCoverManager(context, fileSystem),
            Json,
            XML.v1 {},
            NoLocalVolumes,
            locks,
        )
        val refresh = UpdateMangaFromRemote(
            sourceManager = mockk<SourceManager> { coEvery { getOrStub(LocalSource.ID) } returns source },
            chapterRepository = chapterRepository,
            mangaRepository = mangaRepository,
            syncChaptersWithSource = SyncChaptersWithSource(
                downloadManager = mockk(relaxed = true),
                downloadProvider = mockk(relaxed = true),
                chapterRepository = chapterRepository,
                shouldUpdateDbChapter = ShouldUpdateDbChapter(),
                updateManga = mockk<UpdateManga>(relaxed = true) {
                    coEvery { awaitUpdateFetchIntervalAndLastUpdate(any(), any(), any(), any(), any()) } returns true
                },
                getExcludedScanlators = mockk<GetExcludedScanlators> { coEvery { await(any()) } returns emptySet() },
                libraryPreferences = libraryPreferences,
                chapterNumberOverrides = numberOverrides,
                chapterCopyMarks = copyMarks,
                localTombstones = tombstones,
            ),
            coverCache = mockk(relaxed = true),
            libraryPreferences = libraryPreferences,
            downloadManager = mockk(relaxed = true),
            seriesLocks = locks,
        )
        private val removal = mockk<RemoveMangaFromLibrary> {
            coEvery { takeOut(any()) } returns emptyMap()
            coEvery { restore(any()) } returns Unit
            coEvery { dropCovers(any()) } returns Unit
            coEvery { await(any()) } returns emptyList()
        }
        private val mangaAdder =
            mockk<MangaLibraryAdder> { coEvery { addToBatch(any(), any()) } returns AddOutcome.Added }
        val combine = CombineLocalVolumes(
            context = context,
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
            localFiles = fileSystem,
            localManga = source,
            localNovels = mockk(),
            updateMangaFromRemote = refresh,
            removeManga = removal,
            novelRepository = mockk(),
            novelChapterRepository = mockk(),
            storageManager = storage,
            novelSources = mockk(),
            libraryPreferences = libraryPreferences,
            syncRules = NovelChapterSyncRules(numberOverrides, copyMarks, tombstones),
            tombstones = tombstoneRepository,
            coverCache = mockk(relaxed = true),
            novelDownloadManager = { mockk() },
            removeNovels = mockk(),
            chapterMoves = chapterMoves,
            networkToLocalManga = NetworkToLocalManga(mangaRepository),
            mangaAdder = mangaAdder,
            novelAdder = mockk(),
            setEntryTitle = mockk(relaxed = true),
            localVolumes = NoLocalVolumes,
            journal = journal,
            seriesLocks = locks,
            appScope = scope,
        )
        val import = LocalImport(
            context = context,
            storageManager = storage,
            localNovels = mockk(),
            novelRepository = mockk(),
            novelChapterRepository = mockk(),
            libraryPreferences = libraryPreferences,
            syncRules = NovelChapterSyncRules(numberOverrides, copyMarks, tombstones),
            journal = journal,
            novelAdder = mockk(),
            mangaAdder = mangaAdder,
            networkToLocalManga = NetworkToLocalManga(mangaRepository),
            updateMangaFromRemote = refresh,
            seriesLocks = locks,
        )
        val relink = LocalSeriesRelink(
            context = context,
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
            novelRepository = NovelRepositoryImpl(database),
            novelChapterRepository = NovelChapterRepositoryImpl(database),
            localFiles = fileSystem,
            storageManager = storage,
            chapterMoves = chapterMoves,
            seriesLocks = locks,
        )
        val replay = LocalFileJournalReplay(context, journal, locks, chapterMoves)

        suspend fun settle() = scope.coroutineContext.job.children.forEach { it.join() }
    }

    private fun picked(folder: File): UniFile = UniFile.fromUri(context, FakeSafProvider.treeUri(folder))!!

    private fun rel(file: File) = file.relativeTo(root).invariantSeparatorsPath

    suspend fun open() {
        FakeSafProvider.install(context, root)
        FakeSafProvider.onCall = faults::onCall
        FakeSafProvider.listingFault = faults::listing
        Class.forName("org.sqlite.JDBC", true, ClassLoader.getSystemClassLoader())
        real.execute(null, "PRAGMA foreign_keys = ON", 0).await()
        Database.Schema.create(real).await()
        database = DatabaseBindings.providesDatabase(driver)
        start()
        repeat(4) { index ->
            val series = "S${word()}"
            if (index % 2 == 0) {
                repeat(2) { v -> repeat(2) { newChapter(listOf(series, "Vol ${v + 1}")) } }
            } else {
                repeat(3) { newChapter(listOf(series)) }
            }
            addSeriesRow(series)
        }
        repeat(2) { newDownloads() }
        audit()
        // Most of the library starts read, so a state lost or handed on shows from the first step.
        repeat(chapters.size) { markStep() }
        trace.clear()
    }

    fun close() {
        FakeSafProvider.onCall = null
        FakeSafProvider.listingFault = null
        if (::app.isInitialized) app.scope.cancel()
        real.close()
    }

    private suspend fun start() {
        app = App()
        app.replay.replay()
    }

    /**
     * Runs steps 0 until [steps], leaving out those not in [keep], and answers the first broken invariant.
     */
    suspend fun run(steps: Int, keep: Set<Int>? = null): WorldFailure? {
        for (step in 0 until steps) {
            if (keep != null && step !in keep) continue
            rng = Random(seed * 1_000_003 + step)
            trace += "step $step:"
            try {
                step()
                faults.on = false
                if (faults.dead) restart()
                app.settle()
                check()
                if (rng.nextInt(5) == 0) audit()
            } catch (e: Throwable) {
                faults.on = false
                violations += "the harness threw ${e.stackTraceToString().take(1500)}"
            }
            if (violations.isNotEmpty()) return failure(step)
        }
        try {
            audit()
        } catch (e: Throwable) {
            violations += "the last audit threw ${e.stackTraceToString().take(1500)}"
        }
        val told = chapters.count { !it.removed && !it.uncertain && it.state != null }
        trace += "end: ${chapters.size} chapters, $told with reading state the checks hold to it"
        return if (violations.isEmpty()) null else failure(steps)
    }

    private fun failure(step: Int): WorldFailure {
        trace += violations.map { "BROKEN: $it" }
        return WorldFailure(step, violations.joinToString("\n"), trace.toList())
    }

    private suspend fun restart() {
        faults.killAt = -1
        faults.sticky.clear()
        pending.clear()
        // Whatever the dead process still ran dies at its next call, before anything new starts.
        runCatching { app.settle() }
        app.scope.cancel()
        faults.dead = false
        trace +=
            "    restart and replay ${real.rows("SELECT count(*) FROM local_file_journal") {
                it.getLong(0)
            }} journal rows"
        start()
    }

    // region steps

    private suspend fun step() {
        faults.rate = listOf(0.0, 0.0, 0.03, 0.1)[rng.nextInt(4)]
        FakeSafProvider.createReturnsExisting = rng.nextInt(4) == 0
        faults.sticky.clear()
        if (rng.nextInt(3) == 0) faults.hidden.clear()
        faults.calls = 0
        faults.killAt = -1
        val steps: List<Pair<Int, suspend () -> Unit>> = listOf(
            14 to ::refreshStep,
            20 to ::markStep,
            6 to ::combineStep,
            6 to ::splitStep,
            6 to ::renameStep,
            5 to ::deleteVolumesStep,
            10 to ::undoStep,
            8 to ::finishStep,
            7 to ::outsidePutBackDeleted,
            5 to ::importStep,
            2 to ::outsideRenameChapter,
            1 to ::outsideRenameVolume,
            2 to ::outsideRenameSeries,
            7 to ::outsideDeleteChapter,
            8 to ::outsideRestoreChapter,
            2 to ::outsideMoveChapter,
            2 to ::outsideAddChapter,
            4 to ::relinkStep,
            3 to ::backupStep,
            5 to ::mergeStep,
            1 to ::newDownloadsStep,
        )
        var pick = rng.nextInt(steps.sumOf { it.first })
        val chosen = steps.first { (weight, _) -> (pick < weight).also { pick -= weight } }.second
        chosen()
    }

    /** One in-app operation under this step's faults, perhaps cut off by a kill; what it throws is recorded. */
    private suspend fun inApp(name: String, canKill: Boolean = true, work: suspend () -> Unit) {
        if (canKill && rng.nextInt(8) == 0) faults.killAt = 1 + rng.nextInt(60)
        trace += "  $name (faults ${faults.rate}, existing-on-create ${FakeSafProvider.createReturnsExisting}" +
            (if (faults.killAt > 0) ", kill armed at ${faults.killAt}" else "") + ")"
        faults.on = true
        try {
            withContext(Dispatchers.Default) { withTimeout(60_000) { work() } }
        } catch (e: Throwable) {
            if (!faults.dead && e !is KillError) violations += "$name threw ${e.stackTraceToString().take(1500)}"
        } finally {
            faults.on = false
        }
    }

    private suspend fun refreshStep() {
        val (id, folder) = owned().randomOrNull(rng) ?: return
        inApp("refresh $folder") {
            val result = app.refresh(app.mangaRepository.getMangaById(id), fetchChapters = true)
            trace += "    -> ${if (result.isSuccess) "ok" else "failed: ${result.exceptionOrNull()?.message}"}"
        }
    }

    private suspend fun markStep() {
        val rows = real.rows(
            "SELECT c.id, m.remote_url, c.remote_url, c.user_read, c.user_bookmark, c.user_last_page_read " +
                "FROM chapter c JOIN manga m ON m.id = c.manga_id WHERE m.source_id = 0 ORDER BY c.id",
        ) { listOf(it.getLong(0), it.getString(1), it.getString(2), it.getLong(3), it.getLong(4), it.getLong(5)) }
        val row = rows.randomOrNull(rng) ?: return
        val series = row[1] as String
        val url = row[2] as String
        val state = St(
            read = row[3] == 1L || rng.nextInt(3) > 0,
            bookmark = row[4] == 1L || rng.nextInt(3) == 0,
            page = (row[5] as Long).toInt() + rng.nextInt(4),
        )
        val disk = scan()
        val here = chapters.filter { ch -> !ch.removed && urlsOf(ch, disk).any { it == series to url } } +
            chapters.filter { it.absent?.joinToString("/") == url && it.absent?.first() == series }
        // A row two chapters could answer for (a copy left behind) says nothing about either.
        val chapter = here.singleOrNull()?.takeIf { urlsOf(it, disk).size <= 1 } ?: return
        real.exec(
            "UPDATE chapter SET user_read = ${state.read.int}, user_bookmark = ${state.bookmark.int}, " +
                "user_last_page_read = ${state.page} WHERE id = ${row[0]}",
        )
        chapter.state = state.takeIf { it != FRESH }
        trace += "  mark $url $state (chapter ${chapter.id})"
    }

    private suspend fun combineStep() {
        val all = owned().shuffled(rng)
        if (all.size < 2) return
        val kept = all[0]
        val others = all.drop(1).take(1 + rng.nextInt(2))
        structural(listOf(kept.second) + others.map { it.second })
        inApp("combine ${others.map { it.second }} into ${kept.second}") {
            val series = app.combine.series(ContentType.MANGA, listOf(kept.first) + others.map { it.first })
            val move = app.combine.combine(ContentType.MANGA, series.first(), series.drop(1))
            moved(move, listOf(kept.second) + others.map { it.second })
        }
    }

    private suspend fun splitStep() {
        val (id, folder) = owned().filter { volumesOf(it.second).isNotEmpty() }.randomOrNull(rng) ?: return
        val keys = volumesOf(folder).shuffled(rng).take(1 + rng.nextInt(2))
        val title = if (rng.nextInt(5) == 0) seriesOnDisk().random(rng) else "Split${word()}"
        structural(listOf(folder, title))
        inApp("split $keys of $folder out as $title") {
            val series = app.combine.series(ContentType.MANGA, listOf(id)).single()
            moved(app.combine.splitOut(ContentType.MANGA, series, keys, title), listOf(folder, title))
        }
    }

    private suspend fun renameStep() {
        val (id, folder) = owned().randomOrNull(rng) ?: return
        val title = if (rng.nextBoolean()) "Ren${word()}" else folder
        val labels = volumesOf(folder).map { it to if (rng.nextBoolean()) "V${++counter}" else it }
        structural(listOf(folder, title))
        inApp("rename files of $folder to $title, $labels") {
            val series = app.combine.series(ContentType.MANGA, listOf(id)).single()
            val names = app.combine.renamePlan(ContentType.MANGA, series, title, labels)
            if (names.changes) moved(app.combine.renameFiles(ContentType.MANGA, series, names), listOf(folder, title))
        }
        // A rename the provider dodged to "name (1)" and then would not take back leaves the folder there, its
        // rows at the old address: the series is stranded as by a rename outside the app, until it is linked.
        val addresses = localManga().map { it.second }.toSet()
        if (!File(local, folder).isDirectory && folder in addresses) {
            seriesOnDisk().filter { it !in addresses }.forEach { renamedOutside.putIfAbsent(it, folder) }
        }
    }

    private suspend fun deleteVolumesStep() {
        val (id, folder) = owned().filter { volumesOf(it.second).isNotEmpty() }.randomOrNull(rng) ?: return
        val keys = volumesOf(folder).shuffled(rng).take(1 + rng.nextInt(2))
        structural(listOf(folder))
        val before = scan()
        val inside = chapters.filter { !it.removed && it.absent == null }.mapNotNull { chapter ->
            val url = urlsOf(chapter, before).singleOrNull()?.second ?: return@mapNotNull null
            (chapter to url).takeIf { keys.any { key -> url.startsWith("$folder/$key/") } }
        }
        chapters.filter { ch -> ch.absent?.let { it.first() == folder && it.getOrNull(1) in keys } == true }
            .forEach { it.removed = true }
        var clean = false
        inApp("delete volumes $keys of $folder") {
            val result = app.combine.deleteVolumes(ContentType.MANGA, id, keys)
            clean = result is LocalMoveResult.Moved
            trace += "    -> ${result::class.simpleName}"
        }
        // The user asked for these to go, and for whatever is put at their names next to start unread: a
        // delete that ran whole has dropped their kept state, which one cut off may not have.
        val after = scan()

        // A cover the local source copied from a page outlives the page, and is not the chapter.
        fun gone(
            chapter: Ch,
        ) = chapter.tokens.none { after[it].orEmpty().any { path -> !path.last().startsWith("cover") } }
        // Only where the listing after the delete ran: a series left with no chapter cannot be listed, and
        // keeps its rows as it does for files deleted outside the app.
        val rows = states("chapter", "remote_url", "user_last_page_read")
        inside.filter { (chapter, _) -> gone(chapter) }.forEach { (chapter, url) ->
            chapter.removed = true
            if (clean && !faults.dead && (folder to url) !in rows) chapter.deletedAt = url.split('/')
        }
    }

    private suspend fun outsidePutBackDeleted() {
        val chapter = chapters.filter { it.deletedAt != null }.randomOrNull(rng) ?: return
        val path = chapter.deletedAt!!
        val folder = File(local, path.joinToString("/"))
        if (folder.exists() || !File(local, path.first()).isDirectory) return
        writeChapter(folder, chapter)
        chapter.deletedAt = null
        chapter.removed = false
        chapter.uncertain = false
        chapter.state = null
        trace += "  outside: put back ${rel(folder)}, deleted in the app, expecting it unread"
    }

    private fun moved(move: LocalVolumeMove, series: List<String>) {
        trace += "    -> ${move.result}"
        if (move.result is LocalMoveResult.Moved) pending += move to series
    }

    private suspend fun undoStep() {
        val (move, series) = pending.randomOrNull(rng) ?: return
        pending.removeIf { it.first === move }
        structural(series)
        inApp("undo the move over $series") { trace += "    -> ${move.undo()}" }
    }

    private suspend fun finishStep() {
        val (move, series) = pending.randomOrNull(rng) ?: return
        pending.removeIf { it.first === move }
        inApp("finish the move over $series") {
            move.finish()
            app.settle()
        }
    }

    private suspend fun importStep() {
        // A byte longer than a page, so a Replace only ever takes the place of an earlier imported picture.
        val token = token() + "x"
        val joins = seriesOnDisk().randomOrNull(rng)?.takeIf { rng.nextInt(3) > 0 }
        val replace = joins != null && rng.nextBoolean()
        val name = "extra ${++counter}.png"
        val id = "$counter"
        ImportDocuments.documents[id] = ImportDocuments.Document(name, token.length.toLong(), null, token.toByteArray())
        val uri = ImportDocuments.uri(id)
        val group = ImportGroup(
            key = id,
            type = ContentType.MANGA,
            title = joins ?: "Imp${word()}",
            author = null,
            cover = null,
            files = listOf(ImportFile(uri, name, token.length.toLong())),
            canBeEitherType = false,
            existing = joins,
            duplicates = if (replace) setOf(uri) else emptySet(),
            onDuplicate = DuplicateChoice.Replace,
        )
        // A Replace takes the place of a picture of the same size the folder holds, which the user chose.
        val replaceable = if (replace) {
            File(local, joins!!).listFiles().orEmpty().filter { it.isFile }.mapNotNull { tokenOf(it) }.filter {
                it in
                    loose
            }
        } else {
            emptyList()
        }
        inApp("import $name into ${joins ?: "a new series"}${if (replace) ", replacing" else ""}") {
            val result = app.import.import(listOf(group), addToLibrary = true, emptyList())
            trace += "    -> ${result.series.map { it.path }} ${result.problems.map { it.reason }}"
        }
        if (faults.dead) restart()
        val disk = scan()
        if (token in disk) loose += token
        loose -= replaceable.filter { it !in disk }.toSet()
    }

    private suspend fun outsideRenameChapter() {
        val (chapter, folder) = visibleChapterFolders().randomOrNull(rng) ?: return
        val to = File(folder.parentFile, "${folder.name} r${++counter}")
        if (!folder.renameTo(to)) return
        chapter.uncertain = true
        structural(listOf(rel(folder).split('/')[2]))
        trace += "  outside: rename ${rel(folder)} to ${to.name}"
    }

    private suspend fun outsideRenameVolume() {
        val series = seriesOnDisk().filter { volumesOf(it).isNotEmpty() }.randomOrNull(rng) ?: return
        val volume = File(local, "$series/${volumesOf(series).random(rng)}")
        val to = File(volume.parentFile, "Vol ${++counter} out")
        val inside = chaptersUnder(volume)
        if (!volume.renameTo(to)) return
        inside.forEach { it.uncertain = true }
        structural(listOf(series))
        trace += "  outside: rename ${rel(volume)} to ${to.name}"
    }

    private suspend fun outsideRenameSeries() {
        val series = owned().randomOrNull(rng)?.second ?: return
        val to = File(local, "Out${word()}")
        if (!File(local, series).renameTo(to)) return
        renamedOutside[to.name] = series
        structural(listOf(series))
        trace += "  outside: rename series $series to ${to.name}"
    }

    private suspend fun outsideDeleteChapter() {
        val (chapter, folder) = visibleChapterFolders().filter { it.first.absent == null }.randomOrNull(rng) ?: return
        val disk = scan()
        // A chapter with a copy elsewhere (left behind, or parked) is not one clean thing to put back.
        if (chapter.tokens.any { token -> disk[token].orEmpty().count { !it.last().startsWith("cover") } != 1 }) return
        // A page a killed run left open cannot be deleted on Windows; the half-deleted chapter is put back whole.
        if (!folder.deleteRecursively()) return writeChapter(folder, chapter)
        chapter.absent = rel(folder).split('/').drop(2)
        trace += "  outside: delete ${rel(folder)}"
    }

    private suspend fun outsideRestoreChapter() {
        val chapter = chapters.filter { it.absent != null && !it.removed }.randomOrNull(rng) ?: return
        val folder = File(local, chapter.absent!!.joinToString("/"))
        if (folder.exists() || folder.parentFile?.isDirectory != true) return
        writeChapter(folder, chapter)
        chapter.absent = null
        trace += "  outside: put back ${rel(folder)}${if (chapter.uncertain) "" else " expecting ${chapter.state}"}"
    }

    private suspend fun outsideMoveChapter() {
        val (chapter, folder) = visibleChapterFolders().randomOrNull(rng) ?: return
        val series = rel(folder).split('/')[2]
        val volume = volumesOf(series).randomOrNull(rng) ?: return
        val to = File(local, "$series/$volume/${folder.name}")
        if (to.exists() || !folder.renameTo(to)) return
        chapter.uncertain = true
        structural(listOf(series))
        trace += "  outside: move ${rel(folder)} into $volume"
    }

    private suspend fun outsideAddChapter() {
        val series = seriesOnDisk().randomOrNull(rng) ?: return
        val volume = volumesOf(series).randomOrNull(rng)?.takeIf { rng.nextBoolean() }
        // Beside volume folders a loose chapter is fine, but never a volume inside a series of loose chapters only.
        val chapter = newChapter(listOfNotNull(series, volume))
        structural(listOf(series))
        trace += "  outside: add chapter ${chapter.id} to $series${volume?.let { "/$it" }.orEmpty()}"
    }

    private suspend fun relinkStep() {
        val stranded = localManga().filter { !File(local, it.second).exists() }
        val (id, folder) = stranded.randomOrNull(rng) ?: return
        inApp("relink $folder") {
            val candidates = app.relink.candidates(ContentType.MANGA, id)
            trace += "    candidates $candidates"
            val to = candidates?.randomOrNull(rng) ?: return@inApp
            trace += "    -> ${app.relink.relink(ContentType.MANGA, id, to)}"
        }
    }

    private suspend fun backupStep() {
        val wipe = rng.nextBoolean()
        trace += "  backup, ${if (wipe) "clear the library, " else ""}restore"
        val creator = MangaBackupCreator(
            manualOrder = FakeLibraryManualOrderRepository(),
            mangaRepository = mockk(),
            chapterRepository = app.chapterRepository,
            trackRepository = mockk(),
            categoryRepository = mockk(),
            historyRepository = mockk(),
            mangaMetadataRepository = mockk(),
            customMangaInfoRepository = mockk(),
            getFavorites = mockk(),
            getManga = mockk(),
            mergeGroupRepository = mockk(),
            chapterNumberOverrides = NoChapterNumberOverrides,
            copyMarks = InMemoryChapterCopyMarks(),
            localVolumes = NoLocalVolumes,
            readingLog = NoReadingLog,
            tombstones = app.tombstoneRepository,
        )
        val batch = localManga().map { (id, url) ->
            Manga.create().copy(id = id) to BackupManga(source = LocalSource.ID, url = url, title = url)
        }
        creator.chapters(batch)
        if (wipe) {
            real.exec("DELETE FROM manga WHERE source_id = 0")
            pending.clear()
        }
        mangaRestorer(database).restore(batch.map { it.second }, emptyList())
    }

    private suspend fun mergeStep() {
        val folders = downloads.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }
        val from = folders.filter { it.name.startsWith("Old") }.randomOrNull(rng) ?: return
        val into = File(downloads, from.name.replace("Old", "New"))
        val tree = picked(downloads)
        val fromFile = tree.findFile(from.name) ?: return
        val intoFile = tree.findFile(into.name) ?: return
        inApp("merge downloads ${from.name} into ${into.name}") {
            trace += "    -> ${DownloadFolderMerge.merge(context, fromFile, intoFile)}"
        }
    }

    private suspend fun newDownloadsStep() = newDownloads()

    // endregion

    // region model

    private val Boolean.int get() = if (this) 1 else 0

    /** A fresh name part with no digit in it, since a digit in a series or volume name reads as a chapter number. */
    private fun word(): String = (++counter).toString().map { 'a' + (it - '0') }.joinToString("")

    private fun token() = "TOK:%06d".format(++counter)

    private fun newChapter(parent: List<String>): Ch {
        val chapter = Ch(chapters.size, List(1 + rng.nextInt(2)) { token() })
        chapters += chapter
        chapter.tokens.forEach { byToken[it] = chapter }
        writeChapter(File(local, (parent + "Ch ${++counter}").joinToString("/")), chapter)
        return chapter
    }

    private fun writeChapter(folder: File, chapter: Ch) {
        folder.mkdirs()
        chapter.tokens.forEachIndexed { index, token -> File(folder, "00${index + 1}.jpg").writeText(token) }
    }

    private fun newDownloads() {
        val n = ++counter
        val old = File(downloads, "Old$n").apply { mkdirs() }
        val new = File(downloads, "New$n").apply { mkdirs() }
        fun chapter(dir: File, name: String) = File(dir, name).apply { mkdirs() }.let { folder ->
            repeat(2) { page -> File(folder, "00$page.jpg").writeText(token().also { loose += it }) }
        }
        chapter(old, "Ch 1")
        chapter(old, "Ch 2")
        chapter(old, "Ch 3_tmp")
        chapter(new, "Ch 2")
        chapter(new, "Ch 4")
        trace += "  outside: downloads Old$n and New$n"
    }

    private suspend fun addSeriesRow(folder: String) = real.exec(
        "INSERT INTO manga(source_id, remote_url, remote_title, remote_status, state_initialized, " +
            "user_reader_flags, user_chapter_flags, user_favorite_at, remote_update_strategy, " +
            "state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
            "(${LocalSource.ID}, '$folder', '$folder', 0, 0, 0, 0, 0, 0, 0, '', '{}')",
    )

    /** An edit to [series]' layout: a chapter deleted before it no longer has one address to come back to. */
    private fun structural(series: List<String>) {
        chapters.filter { it.absent?.first() in series }.forEach { it.uncertain = true }
        chapters.filter { it.deletedAt?.first() in series }.forEach { it.deletedAt = null }
    }

    private fun tokenOf(file: File): String? =
        file.takeIf { it.length() in 10L..11L }?.readText()?.takeIf { it.startsWith("TOK:") }

    /** Every token on disk with every place it sits, as path parts under the root. */
    private fun scan(): Map<String, List<List<String>>> = root.walkTopDown().filter { it.isFile }
        .mapNotNull { file -> tokenOf(file)?.let { it to rel(file).split('/') } }
        .groupBy({ it.first }, { it.second })

    /** The (series folder, chapter address) pairs the chapter's pages are listed at: under no hidden folder. */
    private fun urlsOf(chapter: Ch, disk: Map<String, List<List<String>>>): Set<Pair<String, String>> =
        chapter.tokens.flatMap { disk[it].orEmpty() }
            // A cover the local source copied from a page is that page's bytes under another name, and no chapter.
            .filter { it.take(2) == listOf("Reikai", "local") && it.size in 5..6 && !it.last().startsWith("cover") }
            .filter { it.none { part -> part.startsWith('.') } }
            .map { it[2] to it.subList(2, it.size - 1).joinToString("/") }
            .toSet()

    private fun visibleChapterFolders(): List<Pair<Ch, File>> {
        val disk = scan()
        return chapters.filter { !it.removed }.flatMap { ch -> urlsOf(ch, disk).map { ch to File(local, it.second) } }
            .sortedBy { it.second.path }
    }

    private fun chaptersUnder(folder: File): List<Ch> =
        folder.walkTopDown().filter { it.isFile }.mapNotNull { tokenOf(it)?.let(byToken::get) }.toList()

    private fun seriesOnDisk(): List<String> =
        local.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') }.map { it.name }.sorted()

    /** The volume folders of [series]: its folders that hold chapter folders. */
    private fun volumesOf(series: String): List<String> = File(local, series).listFiles().orEmpty()
        .filter { dir ->
            dir.isDirectory && !dir.name.startsWith('.') &&
                dir.listFiles().orEmpty().any { it.isDirectory }
        }
        .map { it.name }.sorted()

    private suspend fun localManga(): List<Pair<Long, String>> =
        real.rows("SELECT id, remote_url FROM manga WHERE source_id = 0 ORDER BY id") {
            it.getLong(0)!! to
                it.getString(1)!!
        }

    /** The local series whose folder is where its row says. */
    private suspend fun owned(): List<Pair<Long, String>> = localManga().filter { File(local, it.second).isDirectory }

    // endregion

    // region invariants

    private suspend fun states(table: String, url: String, page: String): Map<Pair<String, String>, St> = real.rows(
        "SELECT m.remote_url, t.$url, t.${if (table == "chapter") "user_read" else "read"}, " +
            "t.${if (table == "chapter") "user_bookmark" else "bookmark"}, t.$page " +
            "FROM $table t JOIN manga m ON m.id = t.manga_id WHERE m.source_id = 0",
    ) {
        (it.getString(0)!! to it.getString(1)!!) to
            St(it.getLong(2) == 1L, it.getLong(3) == 1L, it.getLong(4)!!.toInt())
    }
        .toMap()

    private suspend fun check(strict: Set<String> = emptySet()) {
        val disk = scan()
        // (a) no user file is gone that no in-app delete took
        val required = loose + chapters.filter { !it.removed && it.absent == null }.flatMap { it.tokens }
        required.filter { it !in disk }.forEach { token ->
            violations += "(a) file $token is gone" + byToken[token]?.let { " (page of chapter ${it.id})" }.orEmpty()
        }
        val rows = states("chapter", "remote_url", "user_last_page_read")
        val kept = states("manga_chapter_tombstone", "url", "last_page_read")
        val ownedSeries = owned().map { it.second }.toSet()
        // A renamed folder opened as a series of its own, before its old entry was linked to it, starts new
        // rows; the old entry keeps the reading state at its old address (named: the relink then refuses it).
        val addresses = localManga().map { it.second }.toSet()
        renamedOutside.entries.removeAll { (folder, was) ->
            (folder in addresses).also { taken ->
                if (taken && was in addresses) {
                    chaptersUnder(File(local, folder)).forEach { it.uncertain = true }
                    chapters.filter { it.absent?.first() == folder }.forEach { it.uncertain = true }
                }
            }
        }
        chapters.filter { !it.removed && !it.uncertain }.forEach { chapter ->
            val urls = urlsOf(chapter, disk).filter { it.first in ownedSeries }
            if (urls.isEmpty()) return@forEach
            val found = urls.associateWith { rows[it] ?: kept[it] }
            val state = chapter.state
            // (b) a chapter whose file is there has its state, on its row or kept for it, at its address
            if (state != null && found.values.none { it == state }) {
                violations += "(b) chapter ${chapter.id} at ${urls.map { it.second }} lost $state: found $found"
            }
            // (c) and no other chapter's
            found.filterValues { it != null && it != state && it != FRESH }.forEach { (url, other) ->
                violations += "(c) chapter ${chapter.id} at ${url.second} carries $other, its own is $state"
            }
            urls.filter { it.first in strict && it !in rows }.forEach {
                violations += "(b) chapter ${chapter.id} at ${it.second} has no row after a clean refresh"
            }
        }
        // A folder set aside is never read as a download.
        downloads.walkTopDown().filter { it.isDirectory && it.name.startsWith(PARKED_PREFIX) }.forEach {
            if (DownloadIndexRules.isIndexed(it.name)) violations += "parked ${rel(it)} would be indexed as a download"
        }
    }

    /** Every series refreshed with no fault, stranded ones linked to their one candidate; then the strict check. */
    private suspend fun audit() {
        trace += "  audit"
        localManga().filter { !File(local, it.second).exists() }.forEach { (id, _) ->
            app.relink.candidates(ContentType.MANGA, id)?.singleOrNull()?.let {
                app.relink.relink(ContentType.MANGA, id, it)
            }
        }
        val clean = owned().filter { (id, _) ->
            app.refresh(app.mangaRepository.getMangaById(id), fetchChapters = true).isSuccess
        }
        app.settle()
        check(strict = clean.map { it.second }.toSet())
    }

    // endregion

    private suspend fun SqlDriver.exec(sql: String) {
        execute(null, sql, 0).await()
    }

    private suspend fun <T> SqlDriver.rows(sql: String, map: (SqlCursor) -> T): List<T> = executeQuery(
        null,
        sql,
        { cursor ->
            QueryResult.Value(
                buildList {
                    while (cursor.next().value) add(map(cursor))
                },
            )
        },
        0,
    ).await()

    private companion object {
        val FRESH = St(read = false, bookmark = false, page = 0)
    }
}
