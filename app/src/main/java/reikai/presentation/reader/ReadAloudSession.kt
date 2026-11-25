package reikai.presentation.reader

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.source.interactor.GetIncognitoState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import reikai.data.novel.tts.NovelTtsSession
import reikai.data.novel.tts.SystemTtsEngine
import reikai.domain.library.ContentType
import reikai.domain.manga.AdultContentChecker
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelReadingOrder
import reikai.domain.novel.NovelRenderingMode
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.DeleteNovelChaptersBehindReader
import reikai.domain.novel.interactor.GetNextNovelChapter
import reikai.domain.novel.interactor.UpsertNovelHistory
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelHistoryUpdate
import reikai.domain.novel.text.NovelWords
import reikai.domain.novel.tts.NovelTtsEngine
import reikai.domain.novel.tts.ReadAloudStop
import reikai.domain.novel.tts.TtsPauseReason
import reikai.domain.novel.tts.TtsPlayback
import reikai.domain.reader.ChapterIncognito
import reikai.domain.reader.ChapterProgress
import reikai.domain.reader.Listening
import reikai.domain.reader.ReadAloudProgress
import reikai.domain.reader.ReadSessionClock
import reikai.domain.readinglog.ReadingLogRepository
import reikai.domain.readinglog.ReadingPaceRepository
import reikai.domain.source.SourceKey
import reikai.novel.download.NovelDownloadManager
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.EmptyChapterException
import reikai.novel.source.NovelChapterText
import reikai.novel.source.NovelChapterTextCache
import reikai.novel.source.NovelChapterTextLoader
import reikai.novel.source.NovelSourceManager
import reikai.presentation.reader.text.NovelChapterFinish
import reikai.presentation.reader.text.chapterParagraphs
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * The novels read aloud reads: [novelId] as the reader opened it, its merge group's [memberIds], in
 * [sourceScoped] or group scope. A reader belongs to it when its novel is one of these in the same scope.
 */
data class ReadAloudSeries(val novelId: Long, val memberIds: List<Long>, val sourceScoped: Boolean) {

    /** Whether a reader of [novelId] in [sourceScoped] reads these chapters, and so may lend speech its page.
     *  A novel in no group reads the same chapters in either scope. */
    fun includes(novelId: Long, sourceScoped: Boolean): Boolean = when {
        memberIds.size <= 1 -> novelId == this.novelId
        this.sourceScoped != sourceScoped -> false
        sourceScoped -> this.novelId == novelId
        else -> novelId in memberIds
    }

    /** The novel and scope read aloud is reached through: the [readerView] lending it a page, else its own. */
    fun openedAs(readerView: Pair<Long, Boolean>?): Pair<Long, Boolean> =
        readerView?.takeIf { (id, scoped) -> includes(id, scoped) } ?: (novelId to sourceScoped)

    /** Whether opening [novelId], in either scope, opens a novel speech is reading. */
    fun touches(novelId: Long): Boolean = novelId == this.novelId || novelId in memberIds
}

/** Read aloud as the app has it: the series being read, null while none is, and where speech is. */
data class ReadAloudSessionState(
    val series: ReadAloudSeries? = null,
    val speech: ReadAloudState = ReadAloudState(),
    /** The voice's pace measured since play, which the time left counts down at. */
    val msPerWord: Double? = null,
) {
    fun isOf(novelId: Long, sourceScoped: Boolean): Boolean = series?.includes(novelId, sourceScoped) == true

    /**
     * The novel and scope a launch of [novelId] in [sourceScoped] reads. One that [follows] read aloud into the
     * series it reads takes read aloud's view of it, so the reader it opens is read aloud's by construction:
     * a resume is a request for what is playing.
     */
    fun viewFor(novelId: Long, sourceScoped: Boolean, follows: Boolean): Pair<Long, Boolean> {
        val series = series?.takeIf { follows && it.touches(novelId) && !it.includes(novelId, sourceScoped) }
            ?: return novelId to sourceScoped
        return if (series.sourceScoped) series.novelId to true else novelId to false
    }
}

/** Speech is on [chapterId] of [series], wherever it got there from, which a reader of the series follows. */
data class ReadAloudMove(val series: ReadAloudSeries, val chapterId: Long)

/** Builds the voice, which a test fakes. */
fun interface ReadAloudEngines {
    fun create(enginePackage: String, onInit: (ready: Boolean) -> Unit): NovelTtsEngine
}

/** A chapter's paragraphs as read aloud counts them, from its processed text and base URL. */
fun interface ReadAloudText {
    fun paragraphs(html: String, baseUrl: String?): List<String>
}

/**
 * Read aloud for the app, outliving the reader it started in: what speech decides (which paragraph and
 * chapter, its place, its finish, its listening time and credit) is owned here, and a reader of the
 * series lends a surface while it is open. One series at a time; starting another replaces it. Each
 * session runs on its own child of [appScope], cancelled when it stops, so nothing of it is left running.
 * See docs/dev/subsystems/novel-reader-rendering.md "Read aloud".
 */
@SingleIn(AppScope::class)
@Inject
class ReadAloudSession(
    @AppCoroutineScope private val appScope: CoroutineScope,
    private val context: Context,
    private val novelPreferences: NovelPreferences,
    private val novelRepo: NovelRepository,
    private val chapterRepo: NovelChapterRepository,
    private val sourceManager: NovelSourceManager,
    private val installer: LnPluginInstaller,
    private val chapterTextCache: NovelChapterTextCache,
    private val downloadManagerProvider: () -> NovelDownloadManager,
    private val orderResolver: NovelReadingOrderResolver,
    private val progressSlot: NovelProgressSlot,
    private val chapterFinish: NovelChapterFinish,
    private val upsertNovelHistory: UpsertNovelHistory,
    private val readingLog: ReadingLogRepository,
    private val paceRepository: ReadingPaceRepository,
    private val getIncognitoState: GetIncognitoState,
    private val adultChecker: AdultContentChecker,
    private val getNextNovelChapter: GetNextNovelChapter,
    private val deleteChaptersBehindReader: DeleteNovelChaptersBehindReader,
    // Unbound in the graph, so each default is what the app runs on; a test passes its own.
    private val engines: ReadAloudEngines = ReadAloudEngines { pkg, onInit -> SystemTtsEngine(context, pkg, onInit) },
    private val text: ReadAloudText = ReadAloudText(::chapterParagraphs),
    private val notification: ReadAloudNotification = NovelTtsSessionTransport(context),
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val clock: Clock = Clock.System,
) {

    val state: StateFlow<ReadAloudSessionState>
        field = MutableStateFlow(ReadAloudSessionState())

    /**
     * The chapter speech is on, null while nothing is read aloud: the reader's chapter as a session starts,
     * then each chapter it moves on to (once that chapter has loaded, before a paragraph of it is spoken) and
     * each it begins being read from, so it is the one answer to where read aloud is.
     */
    val moves: StateFlow<ReadAloudMove?>
        field = MutableStateFlow<ReadAloudMove?>(null)

    private var running: Running? = null

    /** The readers open, by the link each reads through, with whether it is on screen. */
    private val readers = ConcurrentHashMap<ReadAloudLink, Boolean>()

    private val monotonic = timeSource.markNow()

    private fun nowMs() = monotonic.elapsedNow().inWholeMilliseconds

    // The verbs below are a reader's or the notification's, on the main thread.

    /**
     * Starts reading [series] aloud from what [surface] shows, replacing any session of another. [order] is
     * the reader's, which a finish on the first paragraph spoken already needs; [titles] are the chapters'
     * the reader holds, for the notification.
     */
    fun start(
        series: ReadAloudSeries,
        order: NovelReadingOrder?,
        titles: Map<Long, String>,
        surface: ReadAloudSurface?,
        startChapter: Long?,
    ) {
        running?.let { teardown(it) }
        running = Running(series, order).also { session ->
            session.titles.putAll(titles)
            session.readerView = series.novelId to series.sourceScoped
            // Where read aloud is until it speaks: a reopen before then follows to this chapter.
            startChapter?.let { moves.value = ReadAloudMove(series, it) }
            surface?.let(session.controller::attach)
            state.value = ReadAloudSessionState(series)
        }
    }

    fun play() = running?.controller?.play()

    fun readFromHere() = running?.controller?.readFromHere()

    fun pause() {
        running?.pauseFor(TtsPauseReason.User)
    }

    fun stop() {
        running?.controller?.stop()
    }

    fun nextParagraph() = running?.controller?.nextParagraph()

    fun previousParagraph() = running?.controller?.previousParagraph()

    /** [view] is the lending reader's novel and scope, which the notification then reopens. */
    fun attach(surface: ReadAloudSurface, view: Pair<Long, Boolean>) {
        val session = running ?: return
        session.readerView = view
        session.controller.attach(surface)
    }

    fun detach(surface: ReadAloudSurface) = running?.controller?.detach(surface)

    fun onRendererLanded() = running?.controller?.onRendererLanded()

    fun onWindowChanged() = running?.controller?.onWindowChanged()

    /** Whether [chapterId] is the chapter being spoken, or another source's copy of it in the series' stitch. */
    fun isSpoken(chapterId: Long): Boolean {
        val spoken = moves.value?.chapterId ?: return false
        if (chapterId == spoken) return true
        val units = running?.order?.stitch?.associate { it.chapterId to it.unit } ?: return false
        return units[chapterId]?.let { it == units[spoken] } == true
    }

    /** A reader opened, which can follow speech and drains the deletes its reading queued. */
    fun register(reader: ReadAloudLink) {
        readers[reader] = true
    }

    fun unregister(reader: ReadAloudLink) {
        readers.remove(reader)
        running?.holdPausedStop()
    }

    fun setOnScreen(reader: ReadAloudLink, onScreen: Boolean) {
        if (readers.containsKey(reader)) readers[reader] = onScreen
        // The paused stop waits only while nobody is looking, so a reader coming or going decides it again.
        running?.holdPausedStop()
    }

    private fun readerOf(series: ReadAloudSeries, onScreen: Boolean): Boolean =
        readers.any { (reader, shown) -> (!onScreen || shown) && series.includes(reader.novelId, reader.sourceScoped) }

    /** One read aloud session: the speech, and the records it keeps while the series is read. */
    private inner class Running(val series: ReadAloudSeries, startOrder: NovelReadingOrder?) {

        val job = SupervisorJob(appScope.coroutineContext[Job])
        val scope = CoroutineScope(appScope.coroutineContext + job + Dispatchers.Main.immediate)

        /**
         * Asked afresh for every write, since a session runs for hours and incognito turned on meanwhile
         * must keep what follows out of History, the read marks and the trackers.
         */
        fun incognito() = ChapterIncognito(
            sourceOf = { owner -> novelRepo.getById(owner)?.source?.let(SourceKey::Novel) },
            isIncognito = getIncognitoState::await,
        )

        val textLoader = NovelChapterTextLoader(
            context = context,
            novelRepo = novelRepo,
            sourceManager = sourceManager,
            installer = installer,
            preferences = novelPreferences,
            textCache = chapterTextCache,
            readDownloaded = { novel, chapter -> downloadManagerProvider().getChapterText(novel, chapter) },
        )

        /** The renderer the novel keeps for itself, which its text is processed for, as the reader's is. */
        private val ownMode = CompletableDeferred<NovelRenderingMode?>()

        val finishHold = chapterFinish.hold()

        @Volatile var order: NovelReadingOrder? = startOrder

        /** The order read for a write before any was to hand, which every write waiting on it shares. */
        private var orderLoad: Deferred<NovelReadingOrder>? = null

        @Volatile var isAdult = true

        val titles = ConcurrentHashMap<Long, String>()

        var pauseReason = TtsPauseReason.User

        /** Set once the session has stopped, which a write still waiting on the order then sees. */
        @Volatile var ended = false

        /** The view of the reader lending speech its page, which the notification reopens the reader in. */
        @Volatile var readerView: Pair<Long, Boolean>? = null

        /** The time heard of the chapter being spoken, written into History on every pause and chapter end. */
        val heard = ReadSessionClock()
        var heardChapter: Long? = null

        /** History's last stamp, which a chapter reached is stamped after: on a tie History may show either. */
        @Volatile private var lastStamp: Instant? = null
        var listening = Listening()

        private val navigation = Navigation()

        /** The next chapter's text, fetched while the end of this one is spoken, by the chapter it is for. */
        private var prefetch: Pair<Long, Deferred<NovelChapterText>>? = null

        /** Stops a session left paused with no reader of it on screen, see [PAUSED_STOP]. */
        private var pausedStop: Job? = null

        val controller = ReadAloudController(
            scope = scope,
            preferences = novelPreferences,
            createEngine = engines::create,
            navigation = navigation,
            transport = Transport(),
            onStopped = { teardown(this) },
        )

        init {
            controller.state.onEach(::record).launchIn(scope)
            scope.launchIO {
                val novel = try {
                    val owner = getNextNovelChapter.viewerFlags(series.novelId).ownerId
                    val novel = novelRepo.getById(series.novelId)
                    val ownerRow = if (owner == series.novelId) novel else novelRepo.getById(owner)
                    ownMode.complete(ownerRow?.let { NovelRenderingMode.ofFlags(it.viewerFlags) })
                    novel
                } finally {
                    // Also on a failed read, which follows the setting, so a load never waits on it for good.
                    ownMode.complete(null)
                }
                novel?.let { isAdult = it.id in adultChecker.adultNovelIdsAmong(listOf(it)) }
            }
        }

        private fun writeContext(order: NovelReadingOrder) =
            NovelWriteContext(order.memberIds, order.stitch, order.orderedIds, incognito())

        /** Writes waiting on the order; while any is, later ones queue behind it, so none lands out of turn. */
        private var waiting = 0

        /**
         * Runs [write] with the order it trims and marks copies by, reading the order first in the one case a
         * reader had none to hand over. A waiting write runs on the progress lane, outside this session's scope,
         * so it keeps its turn and a stop meanwhile cannot drop it; one whose read failed is logged and lost.
         */
        private fun withOrder(chapterId: Long, write: (NovelWriteContext) -> Unit) {
            val ready = order
            if (ready != null && waiting == 0) return write(writeContext(ready))
            waiting++
            progressSlot.launch {
                try {
                    write(writeContext(order ?: awaitOrder(chapterId)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Read aloud wrote nothing for chapter $chapterId" }
                } finally {
                    waiting--
                }
            }
        }

        /** The order, read once for every write waiting on it; a failed read is forgotten, so the next retries. */
        private suspend fun awaitOrder(chapterId: Long): NovelReadingOrder {
            val load = orderLoad ?: appScope.async {
                orderResolver.resolve(series.novelId, chapterId, series.sourceScoped)
            }.also { orderLoad = it }
            return try {
                load.await().also { order = it }
            } catch (e: Exception) {
                if (orderLoad === load) orderLoad = null
                throw e
            }
        }

        /** A pause for [reason], which also decides the paused stop again when speech was already paused. */
        fun pauseFor(reason: TtsPauseReason) {
            pauseReason = reason
            controller.pause()
            holdPausedStop()
        }

        /** Each step of speech: the listening credit, the time heard, and what a reader is shown. */
        private fun record(speech: ReadAloudState) {
            val playing = speech.playback == TtsPlayback.Playing
            listening = listening.next(playing, speech.progress, nowMs())
            listening.listened?.let { (chapterId, ms) -> credit(chapterId, ms) }
            val chapter = speech.progress?.chapterId
            if (playing && chapter != null) {
                if (chapter != heardChapter) writeHeard()
                heardChapter = chapter
                heard.resume(clock.now())
            } else if (!playing) {
                writeHeard()
            }
            holdPausedStop(speech.playback)
            if (playing) speech.progress?.let(::prefetchNear)
            speech.progress?.chapterId?.takeIf { it != moves.value?.chapterId }?.let { chapterId ->
                if (running === this) moves.value = ReadAloudMove(series, chapterId)
            }
            if (running === this) state.value = ReadAloudSessionState(series, speech, listening.msPerWord)
        }

        /**
         * A session paused for [PAUSED_STOP] with no reader of its series on screen stops, so a notification
         * nobody comes back to does not hold the app alive. A call or a navigation prompt gives focus back
         * by itself, so the pause it made is not counted.
         */
        fun holdPausedStop(playback: TtsPlayback = controller.state.value.playback) {
            val counts = playback == TtsPlayback.Paused && pauseReason != TtsPauseReason.TransientFocusLoss
            if (!counts || readerOf(series, onScreen = true)) {
                pausedStop?.cancel()
                pausedStop = null
                return
            }
            if (pausedStop != null) return
            pausedStop = scope.launch {
                delay(PAUSED_STOP)
                controller.stop()
            }
        }

        /** Fetches the next chapter while the last paragraphs of [at]'s are spoken, so it is waiting when they end. */
        private fun prefetchNear(at: ReadAloudProgress) {
            if (at.paragraph < at.paragraphs - PREFETCH_PARAGRAPHS) return
            if (prefetch?.first == at.chapterId) return
            prefetch = at.chapterId to scope.async {
                val next = navigation.chapterAfter(at.chapterId) ?: error("No chapter after ${at.chapterId}")
                withIOContext {
                    val row = chapterRepo.getById(next) ?: error("Chapter not found: $next")
                    textLoader.load(row, ownMode.await())
                }.also { prefetched = next }
            }
        }

        /** The chapter [prefetch] fetched, once it has. */
        @Volatile private var prefetched: Long? = null

        /** [chapterId]'s text, taken from the prefetch when that fetched it, else read now. */
        suspend fun textOf(chapterId: Long, row: NovelChapter): NovelChapterText {
            val fetched = prefetch?.second?.takeIf { prefetched == chapterId || !it.isCompleted }
            if (fetched != null) {
                val text = runCatchingCancellable { fetched.await() }.getOrNull()
                if (text != null && prefetched == chapterId) return text
            }
            return textLoader.load(row, ownMode.await())
        }

        /** History and the reading log for the time heard since the last write, kept through a process kill. */
        fun writeHeard() {
            val chapterId = heardChapter ?: return
            val now = clock.now()
            val duration = heard.take(now)
            if (duration <= 0L) return
            lastStamp = now
            appScope.launch {
                val owner = chapterRepo.getById(chapterId)?.novelId ?: return@launch
                if (incognito().of(owner)) return@launch
                upsertNovelHistory.await(NovelHistoryUpdate(chapterId, now, duration))
                readingLog.record(ContentType.NOVELS, chapterId, now, duration)
            }
        }

        fun credit(chapterId: Long, ms: Long) {
            appScope.launch {
                val owner = chapterRepo.getById(chapterId)?.novelId ?: return@launch
                if (!incognito().of(owner)) paceRepository.addListenedTime(chapterId, ms)
            }
        }

        private inner class Navigation : ReadAloudNavigation {

            override suspend fun chapterAfter(chapterId: Long): Long? {
                // Read again at every chapter end, so a chapter marked, hidden or downloaded since counts.
                val resolved = withIOContext {
                    orderResolver.resolve(series.novelId, chapterId, series.sourceScoped)
                }
                order = resolved
                val after = resolved.chapterAfter(resolved.currentId) ?: return null
                return resolved.copiesToOpen?.idOf(after) ?: after
            }

            // One that lands after the stop is written as a plain place: speech no longer holds it from a reader.
            override fun savePosition(chapterId: Long, share: Double) = withOrder(chapterId) { context ->
                if (ended) {
                    progressSlot.persist(chapterId, ChapterProgress.Percent.of(share), context)
                } else {
                    progressSlot.reportSpoken(chapterId, share, context)
                }
            }

            override fun finishChapter(chapterId: Long) {
                withOrder(chapterId) { progressSlot.finishSpoken(chapterId, it) }
                writeHeard()
            }

            override suspend fun loadParagraphs(chapterId: Long): List<String> = withIOContext {
                val row = chapterRepo.getById(chapterId) ?: error("Chapter not found: $chapterId")
                titles[chapterId] = row.name
                val (html, baseUrl) = try {
                    textOf(chapterId, row)
                } catch (e: EmptyChapterException) {
                    throw Exception(context.stringResource(MR.strings.novel_chapter_empty), e)
                }
                if (!incognito().of(row.novelId)) {
                    paceRepository.setLength(ContentType.NOVELS, chapterId, NovelWords.ofHtml(html))
                }
                withContext(Dispatchers.Default) { text.paragraphs(html, baseUrl) }
            }

            override fun movedTo(chapterId: Long) {
                moves.value = ReadAloudMove(series, chapterId)
                val stampArrival = !readerOf(series, onScreen = true)
                val arrivedAt = clock.now().let { now -> lastStamp?.let { maxOf(now, it + 1.milliseconds) } ?: now }
                if (stampArrival) lastStamp = arrivedAt
                val reached = order
                appScope.launch {
                    val owner = chapterRepo.getById(chapterId)?.also { titles[chapterId] = it.name }?.novelId
                    // History is what a process kill reopens on, so the chapter reached is stamped now when no
                    // reader shows it; with no read time, which the clock writes on the next pause.
                    if (stampArrival && owner != null && !incognito().of(owner)) {
                        upsertNovelHistory.await(NovelHistoryUpdate(chapterId, arrivedAt, 0L))
                    }
                    reached?.let { orderResolver.downloadAhead(it, chapterId, downloadManagerProvider()) }
                }
            }

            override fun titleOf(chapterId: Long): String = titles[chapterId].orEmpty()
        }

        private inner class Transport : ReadAloudTransport {

            override fun connect(
                onPlay: () -> Unit,
                onPause: () -> Unit,
                onStop: () -> Unit,
                onNext: () -> Unit,
                onPrevious: () -> Unit,
                onSeek: (paragraph: Int) -> Unit,
            ) = notification.connect(
                onPlay = onPlay,
                // pauseFor runs the controller's own pause, which is what onPause is.
                onPause = ::pauseFor,
                onStop = onStop,
                onNext = onNext,
                onPrevious = onPrevious,
                onSeek = onSeek,
            )

            override fun publish(
                playback: TtsPlayback,
                chapterId: Long?,
                title: String,
                paragraph: Int,
                paragraphCount: Int,
                stopReason: ReadAloudStop?,
            ) = notification.publish(
                NovelTtsSession.State(
                    playback = playback,
                    title = title,
                    paragraph = paragraph,
                    paragraphCount = paragraphCount,
                    isAdult = isAdult,
                    novelId = series.openedAs(readerView).first,
                    chapterId = chapterId,
                    sourceScoped = series.openedAs(readerView).second,
                    stopReason = stopReason,
                ),
            )

            override fun takeStopAtChapterEnd() = notification.takeStopAtChapterEnd()

            override fun release() = notification.release()
        }
    }

    private companion object {
        /** How long a paused session waits for someone to come back to it. */
        val PAUSED_STOP = 30.minutes

        /** How near a chapter's end, in paragraphs, the next chapter is fetched. */
        const val PREFETCH_PARAGRAPHS = 3
    }

    /**
     * Ends [session]: the time heard and the listening credit written, its held place written and given
     * back to the reader, the deletes its finishes queued drained when no reader of the series is left to.
     */
    private fun teardown(session: Running) {
        if (running !== session) return
        running = null
        session.ended = true
        session.writeHeard()
        session.listening.next(playing = false, progress = null, nowMs = nowMs()).listened?.let { (chapterId, ms) ->
            session.credit(chapterId, ms)
        }
        progressSlot.flush()
        progressSlot.releaseSpokenHolds()
        if (!readerOf(session.series, onScreen = false)) {
            appScope.launch {
                progressSlot.awaitLanded()
                deleteChaptersBehindReader.deletePending()
            }
        }
        session.finishHold.close()
        session.controller.shutdown()
        session.scope.cancel()
        moves.value = null
        state.value = ReadAloudSessionState()
    }
}
