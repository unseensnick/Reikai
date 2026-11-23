package reikai.presentation.reader

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.source.interactor.GetIncognitoState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import reikai.domain.novel.model.NovelHistoryUpdate
import reikai.domain.novel.text.NovelWords
import reikai.domain.novel.tts.NovelTtsEngine
import reikai.domain.novel.tts.ReadAloudStop
import reikai.domain.novel.tts.TtsPauseReason
import reikai.domain.novel.tts.TtsPlayback
import reikai.domain.reader.ChapterIncognito
import reikai.domain.reader.Listening
import reikai.domain.reader.ReadSessionClock
import reikai.domain.readinglog.ReadingLogRepository
import reikai.domain.readinglog.ReadingPaceRepository
import reikai.domain.source.SourceKey
import reikai.novel.download.NovelDownloadManager
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.EmptyChapterException
import reikai.novel.source.NovelChapterTextCache
import reikai.novel.source.NovelChapterTextLoader
import reikai.novel.source.NovelSourceManager
import reikai.presentation.reader.text.NovelChapterFinish
import reikai.presentation.reader.text.chapterParagraphs
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.TimeSource

/**
 * The novels read aloud reads: [novelId] as the reader opened it, its merge group's [memberIds], in
 * [sourceScoped] or group scope. A reader belongs to it when its novel is one of these in the same scope.
 */
data class ReadAloudSeries(val novelId: Long, val memberIds: List<Long>, val sourceScoped: Boolean) {

    fun includes(novelId: Long, sourceScoped: Boolean): Boolean = this.sourceScoped == sourceScoped &&
        if (sourceScoped) this.novelId == novelId else novelId in memberIds
}

/** Read aloud as the app has it: the series being read, null while none is, and where speech is. */
data class ReadAloudSessionState(
    val series: ReadAloudSeries? = null,
    val speech: ReadAloudState = ReadAloudState(),
    /** The voice's pace measured since play, which the time left counts down at. */
    val msPerWord: Double? = null,
) {
    fun isOf(novelId: Long, sourceScoped: Boolean): Boolean = series?.includes(novelId, sourceScoped) == true
}

/** Speech moved on to [chapterId] of [series] by itself, which a reader of the series follows. */
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

    /** The chapter speech last moved on to by itself, null while nothing is read aloud. */
    val moves: StateFlow<ReadAloudMove?>
        field = MutableStateFlow<ReadAloudMove?>(null)

    private var running: Running? = null

    /** The readers open, by the link each reads through, with whether it is on screen. */
    private val readers = ConcurrentHashMap<ReadAloudLink, Boolean>()

    private val monotonic = timeSource.markNow()

    private fun nowMs() = monotonic.elapsedNow().inWholeMilliseconds

    // The verbs below are a reader's or the notification's, on the main thread.

    /** Starts reading [series] aloud from what [surface] shows, replacing any session of another. */
    fun start(series: ReadAloudSeries, surface: ReadAloudSurface?) {
        running?.let { teardown(it) }
        running = Running(series).also { session ->
            surface?.let(session.controller::attach)
            state.value = ReadAloudSessionState(series)
        }
    }

    fun play() = running?.controller?.play()

    fun readFromHere() = running?.controller?.readFromHere()

    fun pause(reason: TtsPauseReason = TtsPauseReason.User) {
        val session = running ?: return
        session.pauseReason = reason
        session.controller.pause()
    }

    fun stop() {
        running?.controller?.stop()
    }

    fun nextParagraph() = running?.controller?.nextParagraph()

    fun previousParagraph() = running?.controller?.previousParagraph()

    fun attach(surface: ReadAloudSurface) = running?.controller?.attach(surface)

    fun detach(surface: ReadAloudSurface) = running?.controller?.detach(surface)

    fun onRendererLanded() = running?.controller?.onRendererLanded()

    fun onWindowChanged() = running?.controller?.onWindowChanged()

    /** A reader opened, which can follow speech and drains the deletes its reading queued. */
    fun register(reader: ReadAloudLink) {
        readers[reader] = true
    }

    fun unregister(reader: ReadAloudLink) {
        readers.remove(reader)
    }

    fun setOnScreen(reader: ReadAloudLink, onScreen: Boolean) {
        if (readers.containsKey(reader)) readers[reader] = onScreen
    }

    private fun readerOf(series: ReadAloudSeries, onScreen: Boolean): Boolean =
        readers.any { (reader, shown) -> (!onScreen || shown) && series.includes(reader.novelId, reader.sourceScoped) }

    /** One read aloud session: the speech, and the records it keeps while the series is read. */
    private inner class Running(val series: ReadAloudSeries) {

        val job = SupervisorJob(appScope.coroutineContext[Job])
        val scope = CoroutineScope(appScope.coroutineContext + job + Dispatchers.Main.immediate)

        val incognito = ChapterIncognito(
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

        val finishHold = chapterFinish.hold()

        @Volatile var order: NovelReadingOrder? = null

        @Volatile var isAdult = true

        val titles = ConcurrentHashMap<Long, String>()

        var pauseReason = TtsPauseReason.User

        /** The time heard of the chapter being spoken, written into History on every pause and chapter end. */
        val heard = ReadSessionClock()
        var heardChapter: Long? = null
        var listening = Listening()

        val controller = ReadAloudController(
            scope = scope,
            preferences = novelPreferences,
            createEngine = engines::create,
            navigation = Navigation(),
            transport = Transport(),
            onStopped = { teardown(this) },
        )

        init {
            controller.state.onEach(::record).launchIn(scope)
            scope.launch {
                val owner = getNextNovelChapter.viewerFlags(series.novelId).ownerId
                val novel = novelRepo.getById(series.novelId)
                val ownerRow = if (owner == series.novelId) novel else novelRepo.getById(owner)
                ownerRow?.let { textLoader.renderingMode = NovelRenderingMode.ofFlags(it.viewerFlags) }
                novel?.let { isAdult = it.id in adultChecker.adultNovelIdsAmong(listOf(it)) }
            }
        }

        fun writeContext(): NovelWriteContext = NovelWriteContext(
            memberIds = order?.memberIds ?: series.memberIds,
            stitch = order?.stitch.orEmpty(),
            orderedIds = order?.orderedIds.orEmpty(),
            incognito = incognito,
        )

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
            if (running === this) state.value = ReadAloudSessionState(series, speech, listening.msPerWord)
        }

        /** History and the reading log for the time heard since the last write, kept through a process kill. */
        fun writeHeard() {
            val chapterId = heardChapter ?: return
            val now = clock.now()
            val duration = heard.take(now)
            if (duration <= 0L) return
            appScope.launch {
                val owner = chapterRepo.getById(chapterId)?.novelId ?: return@launch
                if (incognito.of(owner)) return@launch
                upsertNovelHistory.await(NovelHistoryUpdate(chapterId, now, duration))
                readingLog.record(ContentType.NOVELS, chapterId, now, duration)
            }
        }

        private fun credit(chapterId: Long, ms: Long) {
            appScope.launch {
                val owner = chapterRepo.getById(chapterId)?.novelId ?: return@launch
                if (!incognito.of(owner)) paceRepository.addListenedTime(chapterId, ms)
            }
        }

        private inner class Navigation : ReadAloudNavigation {

            override suspend fun chapterAfter(chapterId: Long): Long? {
                // Read again at every chapter end, so a chapter marked, hidden or downloaded since counts.
                val resolved = orderResolver.resolve(series.novelId, chapterId, series.sourceScoped)
                order = resolved
                val after = resolved.chapterAfter(resolved.currentId) ?: return null
                return resolved.copiesToOpen?.idOf(after) ?: after
            }

            override fun savePosition(chapterId: Long, share: Double) =
                progressSlot.reportSpoken(chapterId, share, writeContext())

            override fun finishChapter(chapterId: Long) {
                progressSlot.finishSpoken(chapterId, writeContext())
                writeHeard()
            }

            override suspend fun loadParagraphs(chapterId: Long): List<String> = withContext(Dispatchers.IO) {
                val row = chapterRepo.getById(chapterId) ?: error("Chapter not found: $chapterId")
                titles[chapterId] = row.name
                val (html, baseUrl) = try {
                    textLoader.load(row)
                } catch (e: EmptyChapterException) {
                    throw Exception(context.stringResource(MR.strings.novel_chapter_empty), e)
                }
                if (!incognito.of(row.novelId)) {
                    paceRepository.setLength(ContentType.NOVELS, chapterId, NovelWords.ofHtml(html))
                }
                withContext(Dispatchers.Default) { text.paragraphs(html, baseUrl) }
            }

            override fun movedTo(chapterId: Long) {
                moves.value = ReadAloudMove(series, chapterId)
                val stampArrival = !readerOf(series, onScreen = true)
                val reached = order
                appScope.launch {
                    val owner = chapterRepo.getById(chapterId)?.also { titles[chapterId] = it.name }?.novelId
                    // History is what a process kill reopens on, so the chapter reached is stamped now when no
                    // reader shows it; with no read time, which the clock writes on the next pause.
                    if (stampArrival && owner != null && !incognito.of(owner)) {
                        upsertNovelHistory.await(NovelHistoryUpdate(chapterId, clock.now(), 0L))
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
                onPause = { reason ->
                    pauseReason = reason
                    onPause()
                },
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
                    novelId = series.novelId,
                    chapterId = chapterId,
                    sourceScoped = series.sourceScoped,
                    stopReason = stopReason,
                ),
            )

            override fun takeStopAtChapterEnd() = notification.takeStopAtChapterEnd()

            override fun release() = notification.release()
        }
    }

    /**
     * Ends [session]: the time heard and the listening credit written, its held place written and given
     * back to the reader, the deletes its finishes queued drained when no reader of the series is left to.
     */
    private fun teardown(session: Running) {
        if (running !== session) return
        running = null
        session.writeHeard()
        session.listening.next(playing = false, progress = null, nowMs = nowMs()).listened?.let { (chapterId, ms) ->
            appScope.launch {
                val owner = chapterRepo.getById(chapterId)?.novelId ?: return@launch
                if (!session.incognito.of(owner)) paceRepository.addListenedTime(chapterId, ms)
            }
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
