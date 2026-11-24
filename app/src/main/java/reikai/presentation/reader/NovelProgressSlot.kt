package reikai.presentation.reader

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import mihon.core.metro.AppCoroutineScope
import reikai.domain.merge.ChapterUnit
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.interactor.DeleteNovelChaptersBehindReader
import reikai.domain.novel.model.NovelChapter
import reikai.domain.reader.ChapterIncognito
import reikai.domain.reader.ChapterProgress
import reikai.domain.reader.ReaderProgressSave
import reikai.domain.reader.ReaderProgressWrites
import reikai.domain.reader.isChapterComplete
import reikai.presentation.reader.text.NovelChapterFinish
import java.util.concurrent.ConcurrentHashMap

/**
 * What a held write needs to land, carried with it: the write can run after the reader that reported it has
 * closed, and must not read that reader's state. [orderedIds] is the order the trim behind walks.
 */
data class NovelWriteContext(
    val memberIds: List<Long>,
    val stitch: List<ChapterUnit>,
    val orderedIds: List<Long>,
    val incognito: ChapterIncognito,
)

/**
 * A novel chapter's place as the reader and read aloud both save it: one held write per chapter, written
 * in launch order on one lane. While read aloud holds a chapter's place, a renderer still landing cannot
 * overwrite it; the latest report wins either way, and finishing by speech drops the held paragraph before
 * the end is written. One for the app, so speech going on with no reader saves by the same rules.
 */
@SingleIn(AppScope::class)
@Inject
class NovelProgressSlot(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val chapterRepo: NovelChapterRepository,
    private val novelPreferences: NovelPreferences,
    private val chapterFinish: NovelChapterFinish,
    private val deleteChaptersBehindReader: DeleteNovelChaptersBehindReader,
    // Unbound in the graph, so the default is what the app runs on; a test passes its scheduler's.
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private class Held(val at: ChapterProgress.Percent, val context: NovelWriteContext)

    private inner class Chapter {
        @Volatile var lastSaved: ChapterProgress.Percent? = null

        /** Past the threshold every scroll frame reports a new place, so the reports after a written finish
         *  take the hold, or each frame would write and wake every chapter list. */
        @Volatile var finishWritten = false

        /** Read aloud's place is the last one saved, with no report of the reader's own since. */
        @Volatile var spokenHeld = false

        val save = ReaderProgressSave<Long, Held>(scope) { key, held -> persist(key, held.at, held.context) }
    }

    private val chapters = ConcurrentHashMap<Long, Chapter>()

    private fun chapter(id: Long) = chapters.getOrPut(id) { Chapter() }

    /** Positions not written yet, the latest per chapter, so a write queued before a newer report stores it. */
    private val unwritten = ConcurrentHashMap<Long, ChapterProgress.Percent>()

    private val writes = ReaderProgressWrites(scope)

    /** Every position handed to a write, as it is handed: a reader keeps the one it is on for a process kill. */
    val persisted: SharedFlow<Pair<Long, ChapterProgress.Percent>>
        field = MutableSharedFlow<Pair<Long, ChapterProgress.Percent>>(extraBufferCapacity = 64)

    /**
     * The reader's own report. [landingSettled] false is a renderer still landing, which with Keep in view
     * off reports the screen it was rebuilt on rather than where speech left the place; [settled] is a
     * scroll that has stopped, which writes at once.
     */
    fun report(
        id: Long,
        at: ChapterProgress.Percent,
        settled: Boolean,
        landingSettled: Boolean,
        context: NovelWriteContext,
    ) {
        val state = chapter(id)
        if (state.spokenHeld && !landingSettled) return
        // A repeat is dropped, which a held position for another chapter cannot be: it would have
        // been the last report, so this one would differ from it.
        if (!settled && state.lastSaved == at) return
        state.spokenHeld = false
        state.lastSaved = at
        // A settle is where the scroll stopped, and finishing carries mark-as-read, the sibling marking
        // and the tracker push, so neither waits.
        val finishes = completes(at) && !state.finishWritten
        if (finishes) state.finishWritten = true
        state.save.report(id, Held(at, context), now = settled || finishes)
    }

    /** Read aloud has reached [share] of [id]'s words, which holds the place until the reader moves. */
    fun reportSpoken(id: Long, share: Double, context: NovelWriteContext) {
        val at = ChapterProgress.Percent.of(share)
        val state = chapter(id)
        state.lastSaved = at
        state.spokenHeld = true
        state.save.report(id, Held(at, context), now = completes(at))
    }

    /** Speech reached [id]'s end: a held paragraph written after the end would put the chapter back on it. */
    fun finishSpoken(id: Long, context: NovelWriteContext) {
        discardIf { it == id }
        launch { withContext(io) { finishUnlessRead(id, context) } }
    }

    fun isSpokenHeld(id: Long): Boolean = chapters[id]?.spokenHeld == true

    /** The reader opened a chapter itself, or read aloud stopped: no place is speech's any more. */
    fun releaseSpokenHolds() = chapters.values.forEach { it.spokenHeld = false }

    /** The newest position for [id] the database may not hold yet: one held back, or one waiting to land. */
    fun unstored(id: Long): ChapterProgress.Percent? = chapters[id]?.save?.heldFor(id)?.at ?: unwritten[id]

    /** Writes every held position now. */
    fun flush() = chapters.values.forEach { it.save.flush() }

    /** Drops the held positions of the chapters [predicate] names, for ones a later write supersedes. */
    fun discardIf(predicate: (Long) -> Boolean) = chapters.forEach { (id, state) ->
        if (predicate(id)) state.save.discardIf { true }
    }

    /** [write] on the lane, behind every write launched before it. */
    fun launch(write: suspend () -> Unit): Job = writes.launch(write)

    suspend fun awaitLanded() = writes.awaitLanded()

    /** Persists [at] for [id] on the lane, outliving whoever reported it. Reaching the end marks it read. */
    fun persist(id: Long, at: ChapterProgress.Percent, context: NovelWriteContext) {
        unwritten[id] = at
        persisted.tryEmit(id to at)
        writes.launch { withContext(io) { writeUnwritten(id, at, context) } }
    }

    /** [persist] on the caller's own coroutine, for one already on the lane: a launch from it would wait on itself. */
    suspend fun write(id: Long, at: ChapterProgress.Percent, context: NovelWriteContext) {
        unwritten[id] = at
        persisted.tryEmit(id to at)
        writeUnwritten(id, at, context)
    }

    /** One already read has had its finish, and writing the end again would move its place there. */
    suspend fun finishUnlessRead(id: Long, context: NovelWriteContext) {
        if (chapterRepo.getById(id)?.read != false) return
        write(id, ChapterProgress.Percent.END, context)
    }

    /** The shared completion rule, at the threshold the reader chose. */
    fun completes(at: ChapterProgress.Percent): Boolean =
        at.isChapterComplete(novelPreferences.readerMarkReadPercent().get())

    /**
     * Finishing [chapter] through [NovelChapterFinish]. Reaching the end and mark-read-on-skip both land
     * here, as manga's both go through updateChapterProgressOnComplete. The trim is manga's
     * ReaderViewModel.deleteChapterIfNeeded, at the same point.
     */
    suspend fun markChapterRead(chapter: NovelChapter, context: NovelWriteContext) {
        chapterFinish.finish(chapter, context.memberIds, context.stitch, context.incognito) {
            deleteChaptersBehindReader.await(
                context.orderedIds,
                chapter.id,
                context.memberIds,
                context.stitch,
                context.incognito,
            )
        }
    }

    private suspend fun writeUnwritten(id: Long, at: ChapterProgress.Percent, context: NovelWriteContext) {
        val position = unwritten.remove(id)
        // By the chapter's own owner, which in a window crossing a merged novel's seam may not be the
        // current chapter's.
        val owner = chapterRepo.getById(id)?.novelId
        if (owner == null || context.incognito.of(owner)) return
        position?.let { chapterRepo.setLastTextProgress(id, it.hundredths) }
        // Fetched before marking, so the shared interactor still sees it unread.
        val chapter = chapterRepo.getById(id) ?: return
        if (completes(at)) markChapterRead(chapter, context)
    }
}
