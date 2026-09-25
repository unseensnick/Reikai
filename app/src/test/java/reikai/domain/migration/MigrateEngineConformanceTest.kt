package reikai.domain.migration

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.domain.migration.models.MigrationFlag
import mihon.domain.migration.usecases.MigrateMangaUseCase
import mihon.domain.source.interactor.UpdateMangaFromRemote
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.db.Transactions
import reikai.domain.entry.EntryId
import reikai.domain.manga.MangaMergeManager
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.interactor.InsertNovelTrack
import reikai.domain.novel.interactor.MigrateNovelUseCase
import reikai.domain.novel.interactor.UpdateNovel
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelMigrationFlag
import reikai.domain.novel.model.NovelTrack
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.track.source.SourceTrackerDispatcher
import reikai.novel.download.NovelDownloadManager
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/**
 * The one pin on the two migrate engines, Mihon's MigrateMangaUseCase and its novel twin: each case
 * runs both through the same harness, so a rule cannot hold on one type and drift on the other.
 * The harness fakes storage in memory and reads back what landed, rather than which call was made.
 */
class MigrateEngineConformanceTest {

    // Guards

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `migrating an entry onto itself fails the row`(engine: MigrateEngine) = runTest {
        val outcome = engine.migrate(Setup(), replace = true, targetId = SOURCE)

        outcome.error.shouldBeInstanceOf<IllegalStateException>()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `migrating an entry onto itself writes nothing`(engine: MigrateEngine) = runTest {
        val outcome = engine.migrate(Setup(), replace = true, targetId = SOURCE)

        outcome.swap shouldBe null
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `a missing target source fails the row even when the refresh is skipped`(engine: MigrateEngine) = runTest {
        val outcome = engine.migrate(Setup(targetSourceMissing = true), replace = true)

        outcome.error.shouldBeInstanceOf<IllegalStateException>()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `a failed target refresh fails the row`(engine: MigrateEngine) = runTest {
        // Each harness source fails its refresh, so letting the refresh run is what breaks it.
        val outcome = engine.migrate(Setup(), replace = true, skipTargetRefresh = false)

        outcome.error.shouldBeInstanceOf<Injected>()
    }

    // The favorite swap

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `a replace swaps both entries in one write`(engine: MigrateEngine) = runTest {
        engine.migrate(Setup(), replace = true).swap?.map { it.id to it.favorite } shouldBe
            listOf(SOURCE to false, TARGET to true)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `a replace clears the source's date added, so a later re-add stamps fresh`(engine: MigrateEngine) = runTest {
        engine.migrate(Setup(), replace = true).swap?.single { it.id == SOURCE }?.dateAdded shouldBe 0L
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `a copy leaves the source favorited`(engine: MigrateEngine) = runTest {
        engine.migrate(Setup(), replace = false).swap?.map { it.id } shouldBe listOf(TARGET)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `a failed favorite swap fails the row`(engine: MigrateEngine) = runTest {
        engine.migrate(Setup(swapFails = true), replace = true).error.shouldBeInstanceOf<IllegalStateException>()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `the chapter and viewer flags move onto the target`(engine: MigrateEngine) = runTest {
        val target = engine.migrate(Setup(chapterFlags = 42L, viewerFlags = 7L), replace = false).targetSwap

        (target?.chapterFlags to target?.viewerFlags) shouldBe (42L to 7L)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `the notes flag carries the source's notes`(engine: MigrateEngine) = runTest {
        val outcome = engine.migrate(Setup(notes = "my note"), replace = false, flags = setOf(Flag.NOTES))

        outcome.targetSwap?.notes shouldBe "my note"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `without the notes flag the note stays behind`(engine: MigrateEngine) = runTest {
        engine.migrate(Setup(notes = "my note"), replace = false).targetSwap?.notes shouldBe null
    }

    // The merge group

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `a replace moves the target into the source's place in its group`(engine: MigrateEngine) = runTest {
        engine.migrate(Setup(group = longArrayOf(SOURCE, 7L)), replace = true).groupWrites shouldBe
            listOf("replace $SOURCE->$TARGET")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `a copy of a grouped entry adds the target to the group`(engine: MigrateEngine) = runTest {
        engine.migrate(Setup(group = longArrayOf(SOURCE, 7L)), replace = false).groupWrites shouldBe
            listOf("merge [$SOURCE, 7, $TARGET]")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `a copy of an ungrouped entry groups nothing`(engine: MigrateEngine) = runTest {
        engine.migrate(Setup(group = longArrayOf(SOURCE)), replace = false).groupWrites shouldBe emptyList()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `the group rewrite and the swap share one transaction, swap last`(engine: MigrateEngine) = runTest {
        // Two transactions let a cancelled batch commit the swap and never reach the rewrite; swap
        // first and the departing member is unfavorited before the group dissolves.
        val outcome = engine.migrate(Setup(group = longArrayOf(SOURCE, 7L)), replace = true)

        (outcome.writeOrder to outcome.transactionsCompleted) shouldBe
            (listOf("replace $SOURCE->$TARGET in tx", "swap in tx") to 1)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `a failing swap rolls back the transaction that moved the group`(engine: MigrateEngine) = runTest {
        val outcome = engine.migrate(Setup(group = longArrayOf(SOURCE, 7L), swapFails = true), replace = true)

        (outcome.transactionsEntered to outcome.transactionsCompleted) shouldBe (1 to 0)
    }

    // The chapter carry

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `target chapters at or below the highest read number are marked read`(engine: MigrateEngine) = runTest {
        val setup = Setup(
            sourceChapters = listOf(Ch(1, 1.0, read = true), Ch(2, 2.0, read = true), Ch(3, 3.0, read = true)),
            targetChapters = listOf(Ch(10, 1.5), Ch(11, 2.5), Ch(12, 9.0)),
        )

        engine.migrate(setup, replace = true, flags = setOf(Flag.CHAPTER)).targetChapters.filter { it.read }
            .map { it.id } shouldBe listOf(10L, 11L)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `with nothing read in the source nothing is swept to read`(engine: MigrateEngine) = runTest {
        val setup = Setup(
            sourceChapters = listOf(Ch(1, 1.0, bookmark = true)),
            targetChapters = listOf(Ch(10, 1.0), Ch(11, 2.0)),
        )

        engine.migrate(setup, replace = true, flags = setOf(Flag.CHAPTER)).targetChapters.none { it.read } shouldBe
            true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `a matched chapter takes the source's bookmark and fetch date`(engine: MigrateEngine) = runTest {
        val setup = Setup(
            sourceChapters = listOf(Ch(1, 1.0, bookmark = true, dateFetch = 1234)),
            targetChapters = listOf(Ch(10, 1.0, dateFetch = 9999)),
        )

        engine.migrate(setup, replace = true, flags = setOf(Flag.CHAPTER)).targetChapters.single() shouldBe
            Ch(10, 1.0, bookmark = true, dateFetch = 1234)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `a read target chapter is never un-read`(engine: MigrateEngine) = runTest {
        val setup = Setup(sourceChapters = listOf(Ch(1, 1.0)), targetChapters = listOf(Ch(10, 1.0, read = true)))

        engine.migrate(setup, replace = true, flags = setOf(Flag.CHAPTER)).targetChapters.single().read shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `an unrecognised target chapter number is left alone`(engine: MigrateEngine) = runTest {
        val setup = Setup(sourceChapters = listOf(Ch(1, 1.0, read = true)), targetChapters = listOf(Ch(10, -1.0)))

        engine.migrate(setup, replace = true, flags = setOf(Flag.CHAPTER)).targetChapters.single() shouldBe
            Ch(10, -1.0)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `the further of the two reading positions wins`(engine: MigrateEngine) = runTest {
        val setup = Setup(
            sourceChapters = listOf(Ch(1, 1.0, position = 15), Ch(2, 2.0, position = 90)),
            targetChapters = listOf(Ch(10, 1.0, position = 80), Ch(11, 2.0, position = 2)),
        )

        engine.migrate(setup, replace = true, flags = setOf(Flag.CHAPTER)).targetChapters.map { it.position } shouldBe
            listOf(80L, 90L)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `a failed chapter carry fails the row before the library changes`(engine: MigrateEngine) = runTest {
        val setup = Setup(
            sourceChapters = listOf(Ch(1, 1.0, read = true)),
            targetChapters = listOf(Ch(10, 1.0)),
            carryWriteFails = true,
        )

        val outcome = engine.migrate(setup, replace = true, flags = setOf(Flag.CHAPTER))

        ((outcome.error != null) to outcome.swap) shouldBe (true to null)
    }

    // Cover, downloads and trackers

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `the cover flag copies the custom cover onto the target and bumps its timestamp`(engine: MigrateEngine) =
        runTest {
            val outcome = engine.migrate(Setup(customCover = "COVER-BYTES"), replace = false, flags = setOf(Flag.COVER))

            // Without the bump a target that already had a custom cover keeps showing the old one.
            (outcome.coverOf(TARGET) to outcome.coverBumped) shouldBe ("COVER-BYTES" to listOf(TARGET))
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `without the cover flag the cover stays behind`(engine: MigrateEngine) = runTest {
        engine.migrate(Setup(customCover = "COVER-BYTES"), replace = false).coverOf(TARGET) shouldBe null
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `the remove-download flag deletes the source's downloads`(engine: MigrateEngine) = runTest {
        engine.migrate(Setup(), replace = true, flags = setOf(Flag.REMOVE_DOWNLOAD)).downloadsDeleted shouldBe
            listOf(SOURCE)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `a migration never downloads chapters onto the target`(engine: MigrateEngine) = runTest {
        // A silent re-fetch costs metered data.
        val setup = Setup(sourceChapters = listOf(Ch(1, 1.0)), targetChapters = listOf(Ch(10, 1.0)))

        val outcome = engine.migrate(setup, replace = true, flags = setOf(Flag.CHAPTER, Flag.REMOVE_DOWNLOAD))

        outcome.downloadsQueued shouldBe 0
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `tracker links are re-pointed at the target, keeping their ids`(engine: MigrateEngine) = runTest {
        engine.migrate(Setup(sourceTrackId = 5L), replace = true).tracksWritten shouldBe listOf(5L to TARGET)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `a committed migration is passed to the source tracker`(engine: MigrateEngine) = runTest {
        engine.migrate(Setup(), replace = true, flags = setOf(Flag.CHAPTER)).sourceTrackerCalls shouldBe
            listOf("$SOURCE->$TARGET replace=true chapters=true")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `a failed swap tells the source tracker nothing`(engine: MigrateEngine) = runTest {
        engine.migrate(Setup(swapFails = true), replace = true).sourceTrackerCalls shouldBe emptyList()
    }

    companion object {
        const val SOURCE = 1L
        const val TARGET = 2L

        @JvmStatic
        fun engines() = listOf(MangaEngine(), NovelEngine())
    }
}

/** The migration flags both engines take, named once; each engine maps them onto its own enum. */
enum class Flag { CHAPTER, COVER, NOTES, REMOVE_DOWNLOAD }

/** A chapter in neither type's model: [position] is manga's page or a novel's text offset. */
data class Ch(
    val id: Long,
    val number: Double,
    val read: Boolean = false,
    val bookmark: Boolean = false,
    val dateFetch: Long = 0,
    val position: Long = 0,
)

/** What the stored world looks like before the migration, and which write to break. */
data class Setup(
    val sourceChapters: List<Ch> = emptyList(),
    val targetChapters: List<Ch> = emptyList(),
    val group: LongArray = longArrayOf(),
    val customCover: String? = null,
    val notes: String = "",
    val chapterFlags: Long = 0,
    val viewerFlags: Long = 0,
    val sourceTrackId: Long? = null,
    val targetSourceMissing: Boolean = false,
    val carryWriteFails: Boolean = false,
    val swapFails: Boolean = false,
)

data class Swap(
    val id: Long,
    val favorite: Boolean?,
    val dateAdded: Long?,
    val notes: String?,
    val chapterFlags: Long?,
    val viewerFlags: Long?,
)

/** What landed. [swap] is null unless a favorite swap was written; a failed row reports [error]. */
class Outcome(
    val error: Throwable?,
    val swap: List<Swap>?,
    val targetChapters: List<Ch>,
    val groupWrites: List<String>,
    val writeOrder: List<String>,
    val transactionsEntered: Int,
    val transactionsCompleted: Int,
    private val coverDir: File,
    val coverBumped: List<Long>,
    val downloadsDeleted: List<Long>,
    val downloadsQueued: Int,
    val tracksWritten: List<Pair<Long, Long>>,
    val sourceTrackerCalls: List<String>,
) {
    val targetSwap: Swap? get() = swap?.singleOrNull { it.favorite == true }

    fun coverOf(id: Long): String? = coverFile(coverDir, id).takeIf { it.exists() }?.readText()
}

/** The failure a case injects, so a test can tell it from the engine failing some other way. */
class Injected : RuntimeException("injected")

private fun coverFile(dir: File, id: Long) = File(dir, "cover-$id")

/** Records every write the harness observes, and whether it happened inside a transaction. */
private class Recorder : Transactions {
    var entered = 0
    var completed = 0
    private var inside = false
    val groupWrites = mutableListOf<String>()
    val writeOrder = mutableListOf<String>()
    var swap: List<Swap>? = null
    val coverBumped = mutableListOf<Long>()
    val downloadsDeleted = mutableListOf<Long>()
    var downloadsQueued = 0
    val tracksWritten = mutableListOf<Pair<Long, Long>>()
    val sourceTrackerCalls = mutableListOf<String>()
    val coverDir: File = Files.createTempDirectory("migrate-covers").toFile().apply { deleteOnExit() }

    override suspend fun <T> run(block: suspend () -> T): T {
        entered++
        inside = true
        try {
            return block().also { completed++ }
        } finally {
            inside = false
        }
    }

    fun group(write: String) {
        groupWrites += write
        writeOrder += if (inside) "$write in tx" else write
    }

    fun swap(updates: List<Swap>, succeeds: Boolean): Boolean {
        writeOrder += if (inside) "swap in tx" else "swap"
        if (succeeds) swap = updates
        return succeeds
    }

    fun seedCover(setup: Setup) {
        setup.customCover?.let { coverFile(coverDir, MigrateEngineConformanceTest.SOURCE).writeText(it) }
    }

    fun sourceTracker(from: EntryId, to: EntryId, replace: Boolean, chapters: Boolean) {
        sourceTrackerCalls += "${from.rawId}->${to.rawId} replace=$replace chapters=$chapters"
    }

    fun outcome(error: Throwable?, targetChapters: List<Ch>) = Outcome(
        error = error,
        swap = swap,
        targetChapters = targetChapters,
        groupWrites = groupWrites,
        writeOrder = writeOrder,
        transactionsEntered = entered,
        transactionsCompleted = completed,
        coverDir = coverDir,
        coverBumped = coverBumped,
        downloadsDeleted = downloadsDeleted,
        downloadsQueued = downloadsQueued,
        tracksWritten = tracksWritten,
        sourceTrackerCalls = sourceTrackerCalls,
    )
}

interface MigrateEngine {
    suspend fun migrate(
        setup: Setup,
        replace: Boolean,
        flags: Set<Flag> = emptySet(),
        skipTargetRefresh: Boolean = true,
        targetId: Long = MigrateEngineConformanceTest.TARGET,
    ): Outcome
}

class MangaEngine : MigrateEngine {
    override fun toString() = "manga"

    override suspend fun migrate(
        setup: Setup,
        replace: Boolean,
        flags: Set<Flag>,
        skipTargetRefresh: Boolean,
        targetId: Long,
    ): Outcome {
        val rec = Recorder().apply { seedCover(setup) }
        val chapters = (
            setup.sourceChapters.map { it.toChapter(MigrateEngineConformanceTest.SOURCE) } +
                setup.targetChapters.map { it.toChapter(MigrateEngineConformanceTest.TARGET) }
            ).associateBy { it.id }.toMutableMap()
        val source = mockk<Source>(relaxed = true)
        val sourceManager = mockk<SourceManager>()
        coEvery { sourceManager.get(any()) } returns source.takeUnless { setup.targetSourceMissing }
        val getChapters = mockk<GetChaptersByMangaId> {
            coEvery { await(any(), any()) } answers { chapters.values.filter { it.mangaId == firstArg<Long>() } }
        }
        val chapterRepository = mockk<ChapterRepository> {
            coEvery { updateAll(any()) } answers {
                if (setup.carryWriteFails) throw Injected()
                firstArg<List<ChapterUpdate>>().forEach { u ->
                    val c = chapters.getValue(u.id)
                    chapters[u.id] = c.copy(
                        read = u.read ?: c.read,
                        bookmark = u.bookmark ?: c.bookmark,
                        lastPageRead = u.lastPageRead ?: c.lastPageRead,
                        dateFetch = u.dateFetch ?: c.dateFetch,
                    )
                }
            }
        }
        val updateManga = mockk<UpdateManga>(relaxed = true) {
            coEvery { awaitAll(any<List<MangaUpdate>>()) } answers {
                val updates = firstArg<List<MangaUpdate>>()
                    .map { Swap(it.id, it.favorite, it.dateAdded, it.notes, it.chapterFlags, it.viewerFlags) }
                rec.swap(updates, succeeds = !setup.swapFails)
            }
            coEvery { awaitUpdateCoverLastModified(any()) } answers {
                rec.coverBumped += firstArg<Long>()
                true
            }
        }
        val merge = mockk<MangaMergeManager> {
            coEvery { computeRelatedIds(any()) } returns setup.group
            coEvery { merge(any()) } answers { rec.group("merge ${firstArg<List<Long>>()}") }
            coEvery { replaceInGroup(any(), any()) } answers
                { rec.group("replace ${firstArg<Long>()}->${secondArg<Long>()}") }
        }
        val coverCache = mockk<CoverCache> {
            every { getCustomCoverFile(any<Long>()) } answers { coverFile(rec.coverDir, firstArg<Long>()) }
            every { setCustomCoverToCache(any(), any()) } answers {
                coverFile(rec.coverDir, firstArg<Manga>().id).writeBytes(secondArg<InputStream>().readBytes())
            }
        }
        val downloadManager = mockk<DownloadManager>(relaxed = true) {
            every { deleteManga(any(), any(), any()) } answers { rec.downloadsDeleted += firstArg<Manga>().id }
            coEvery { downloadChapters(any(), any(), any()) } answers { rec.downloadsQueued++ }
        }
        val track = setup.sourceTrackId?.let { mangaTrack(it) }
        val getTracks = mockk<GetTracks> { coEvery { await(any<Long>()) } answers { listOfNotNull(track) } }
        val insertTrack = mockk<InsertTrack> {
            coEvery { awaitAll(any()) } answers
                { firstArg<List<Track>>().forEach { rec.tracksWritten += it.id to it.mangaId } }
        }
        // Reached only when a case lets the refresh run, which is the case that breaks it. Stubbed
        // outside a mockk block, where a bare invoke binds to the matcher scope.
        val updateFromRemote = mockk<UpdateMangaFromRemote>()
        coEvery { updateFromRemote(any<Manga>(), any(), any(), any(), any()) } returns Result.failure(Injected())
        val sourceTracker = mockk<SourceTrackerDispatcher> {
            every { migrated(any(), any(), any(), any()) } answers {
                rec.sourceTracker(firstArg(), secondArg(), thirdArg(), arg(3))
            }
        }
        val useCase = MigrateMangaUseCase(
            sourcePreferences = mockk(relaxed = true),
            trackerManager = mockk(relaxed = true) { every { trackers } returns emptyList() },
            sourceManager = sourceManager,
            downloadManager = downloadManager,
            updateManga = updateManga,
            getChaptersByMangaId = getChapters,
            updateChapter = mockk(relaxed = true),
            getCategories = mockk { coEvery { await(any<Long>()) } returns emptyList() },
            setMangaCategories = mockk(relaxed = true),
            getTracks = getTracks,
            insertTrack = insertTrack,
            coverCache = coverCache,
            updateMangaFromRemote = updateFromRemote,
            mangaMergeManager = merge,
            chapterRepository = chapterRepository,
            transactions = rec,
            sourceTracker = sourceTracker,
        )
        val current = Manga.create().copy(
            id = MigrateEngineConformanceTest.SOURCE,
            source = 1L,
            url = "/1",
            notes = setup.notes,
            chapterFlags = setup.chapterFlags,
            viewerFlags = setup.viewerFlags,
        )
        val target = Manga.create().copy(id = targetId, source = 1L, url = "/$targetId")
        val mangaFlags = flags.map {
            when (it) {
                Flag.CHAPTER -> MigrationFlag.CHAPTER
                Flag.COVER -> MigrationFlag.CUSTOM_COVER
                Flag.NOTES -> MigrationFlag.NOTES
                Flag.REMOVE_DOWNLOAD -> MigrationFlag.REMOVE_DOWNLOAD
            }
        }.toSet()
        val error = runCatching { useCase(current, target, replace, mangaFlags, skipTargetRefresh) }.exceptionOrNull()
        val targetChapters = chapters.values.filter { it.mangaId == MigrateEngineConformanceTest.TARGET }
            .sortedBy { it.id }
            .map { Ch(it.id, it.chapterNumber, it.read, it.bookmark, it.dateFetch, it.lastPageRead) }
        return rec.outcome(error, targetChapters)
    }

    private fun Ch.toChapter(mangaId: Long) = Chapter.create().copy(
        id = id,
        mangaId = mangaId,
        chapterNumber = number,
        read = read,
        bookmark = bookmark,
        dateFetch = dateFetch,
        lastPageRead = position,
    )

    private fun mangaTrack(id: Long) = Track(
        id = id,
        mangaId = MigrateEngineConformanceTest.SOURCE,
        trackerId = 3L,
        remoteId = 9L,
        libraryId = null,
        title = "t",
        lastChapterRead = 0.0,
        totalChapters = 0L,
        status = 0L,
        score = 0.0,
        remoteUrl = "",
        startDate = 0L,
        finishDate = 0L,
        private = false,
    )
}

class NovelEngine : MigrateEngine {
    override fun toString() = "novel"

    override suspend fun migrate(
        setup: Setup,
        replace: Boolean,
        flags: Set<Flag>,
        skipTargetRefresh: Boolean,
        targetId: Long,
    ): Outcome {
        val rec = Recorder().apply { seedCover(setup) }
        val chapters = (
            setup.sourceChapters.map { it.toChapter(MigrateEngineConformanceTest.SOURCE) } +
                setup.targetChapters.map { it.toChapter(MigrateEngineConformanceTest.TARGET) }
            ).associateBy { it.id }.toMutableMap()
        val source = mockk<NovelSource>(relaxed = true) {
            // Reached only when a case lets the refresh run, which is the case that breaks it.
            coEvery { parseNovel(any()) } throws Injected()
        }
        val sourceManager = mockk<NovelSourceManager>()
        coEvery { sourceManager.get(any<String>()) } returns source.takeUnless { setup.targetSourceMissing }
        val chapterRepository = mockk<NovelChapterRepository>(relaxed = true) {
            coEvery { getByNovelId(any()) } answers { chapters.values.filter { it.novelId == firstArg<Long>() } }
            coEvery { updateAll(any()) } answers {
                if (setup.carryWriteFails) {
                    false
                } else {
                    firstArg<List<NovelChapter>>().forEach { chapters[it.id] = it }
                    true
                }
            }
        }
        val novelRepository = mockk<NovelRepository>(relaxed = true) {
            coEvery { updateAll(any()) } answers {
                val updates = firstArg<List<NovelUpdate>>()
                    .map { Swap(it.id, it.favorite, it.dateAdded, it.notes, it.chapterFlags, it.viewerFlags) }
                rec.swap(updates, succeeds = !setup.swapFails)
            }
        }
        val updateNovel = mockk<UpdateNovel>(relaxed = true) {
            coEvery { awaitUpdateCoverLastModified(any()) } answers {
                rec.coverBumped += firstArg<Long>()
                true
            }
        }
        val merge = mockk<NovelMergeManager> {
            coEvery { computeRelatedIds(any()) } returns setup.group
            coEvery { merge(any()) } answers { rec.group("merge ${firstArg<List<Long>>()}") }
            coEvery { replaceInGroup(any(), any()) } answers
                { rec.group("replace ${firstArg<Long>()}->${secondArg<Long>()}") }
        }
        val coverCache = mockk<CoverCache> {
            every { getCustomCoverFile(any<EntryId>()) } answers { coverFile(rec.coverDir, firstArg<EntryId>().rawId) }
        }
        val downloadManager = mockk<NovelDownloadManager>(relaxed = true) {
            coEvery { awaitDeleteNovel(any()) } answers { rec.downloadsDeleted += firstArg<Novel>().id }
            coEvery { downloadChapters(any()) } answers { rec.downloadsQueued++ }
        }
        val track = setup.sourceTrackId?.let { novelTrack(it) }
        val getTracks = mockk<GetNovelTracks> { coEvery { await(any()) } answers { listOfNotNull(track) } }
        val insertTrack = mockk<InsertNovelTrack> {
            coEvery { awaitAll(any()) } answers {
                firstArg<List<NovelTrack>>().forEach { rec.tracksWritten += it.id to it.novelId }
            }
        }
        val sourceTracker = mockk<SourceTrackerDispatcher> {
            every { migrated(any(), any(), any(), any()) } answers {
                rec.sourceTracker(firstArg(), secondArg(), thirdArg(), arg(3))
            }
        }
        val useCase = MigrateNovelUseCase(
            novelChapterRepository = chapterRepository,
            getNovelCategories = mockk(relaxed = true),
            setNovelCategories = mockk(relaxed = true),
            novelMergeManager = merge,
            novelDownloadManagerProvider = { downloadManager },
            updateNovel = updateNovel,
            coverCache = coverCache,
            getNovelTracks = getTracks,
            insertNovelTrack = insertTrack,
            sourceManager = sourceManager,
            novelRepository = novelRepository,
            database = mockk(relaxed = true),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            transactions = rec,
            sourceTracker = sourceTracker,
        )
        val current = Novel.create().copy(
            id = MigrateEngineConformanceTest.SOURCE,
            notes = setup.notes,
            chapterFlags = setup.chapterFlags,
            viewerFlags = setup.viewerFlags,
        )
        val target = Novel.create().copy(id = targetId)
        val novelFlags = flags.map {
            when (it) {
                Flag.CHAPTER -> NovelMigrationFlag.CHAPTER
                Flag.COVER -> NovelMigrationFlag.COVER
                Flag.NOTES -> NovelMigrationFlag.NOTES
                Flag.REMOVE_DOWNLOAD -> NovelMigrationFlag.REMOVE_DOWNLOAD
            }
        }.toSet()
        val error = runCatching { useCase(current, target, novelFlags, replace, skipTargetRefresh) }.exceptionOrNull()
        val targetChapters = chapters.values.filter { it.novelId == MigrateEngineConformanceTest.TARGET }
            .sortedBy { it.id }
            .map { Ch(it.id, it.chapterNumber, it.read, it.bookmark, it.dateFetch, it.lastTextProgress) }
        return rec.outcome(error, targetChapters)
    }

    private fun Ch.toChapter(novelId: Long) = NovelChapter(
        id = id,
        novelId = novelId,
        url = "u$id",
        name = "Chapter $number",
        read = read,
        bookmark = bookmark,
        lastTextProgress = position,
        chapterNumber = number,
        sourceOrder = id,
        dateFetch = dateFetch,
        dateUpload = 0,
        page = "",
    )

    private fun novelTrack(id: Long) = NovelTrack(
        id = id, novelId = MigrateEngineConformanceTest.SOURCE, trackerId = 1, remoteId = 1, libraryId = null,
        title = "t", lastChapterRead = 0.0, totalChapters = 0, status = 0, score = 0.0, remoteUrl = "",
        startDate = 0, finishDate = 0, private = false,
    )
}
