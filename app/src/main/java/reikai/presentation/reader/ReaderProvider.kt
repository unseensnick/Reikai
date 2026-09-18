package reikai.presentation.reader

import android.content.Context
import android.content.Intent
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import kotlinx.coroutines.flow.Flow
import tachiyomi.core.common.preference.Preference

/**
 * One content type's answers for the reader engine. The host is passed per call rather than held, so
 * a provider never outlives the Activity that built its views. Naming `ReaderActivity` is deliberate,
 * since one host serves both types; what must not appear in this file is a manga type.
 *
 * **Every flow here is cold.** The engine holds the provider across an Activity recreation, so state
 * shared in the Activity's scope would freeze the first time the reader is rotated.
 */
interface ReaderProvider {

    /**
     * What the chrome shows for this entry. Neutral so the app bar renders the same way whichever
     * content type is open, rather than the host reaching into one engine's model for a title.
     */
    val chrome: Flow<ReaderChromeState>

    /**
     * The bottom-bar buttons this content type draws, in the order it draws them. Each type stores its
     * own selection and order, so a manga action cannot surface in a novel session or the reverse.
     */
    val bottomButtons: Flow<List<ReaderBottomButton>>

    /** Whose buttons those are, which is the pair of preferences the in-reader editor changes. */
    val bottomButtonScope: ReaderBottomButton.Scope

    /**
     * The colour the entry's cover tints the chrome with, null until found or where the cover gives none.
     * [context] loads the cover and is passed per call, like the host, so the flow holds no Activity.
     */
    fun seedColor(context: Context): Flow<Int?>

    /** This content type's own brightness and colour treatment, which the host applies to the page. */
    val displayFilters: ReaderDisplayFilters

    /**
     * This content type's own fullscreen and draw-under-cutout settings, which the host applies to the
     * window. The session's type is fixed at launch, so which pair answers never changes under it.
     */
    val fullscreen: Preference<Boolean>

    val drawUnderCutout: Preference<Boolean>

    /**
     * Told when the reader moves the page by hand: a drag past the touch slop, or a key the viewport
     * took. A novel open holds the chapter it landed in until then; manga has nothing to release.
     */
    fun onReaderMoved()

    /** Writes a position the session is still holding back. The host calls it on pause, since a
     *  backgrounded reader can be killed before a deferred write runs. */
    fun flushPosition()

    /** Opens the entry's own details page, or null while the entry is not resolved yet. */
    fun detailsIntent(context: Context): Intent?

    /**
     * The colour behind the page, which shows while a chapter loads or fails. Each type has its own
     * theme setting. [context] resolves a follow-system theme against its night mode.
     */
    fun pageBackground(context: Context): Flow<Int>

    /**
     * Where the reader is in the open chapter and which navigator shows it. Both are the session's to
     * answer: a novel picks the rail with a setting of its own, while manga offers it per reading mode.
     */
    val navigator: Flow<ReaderNavigatorState>

    /** Stamps the open chapter into this type's history with the time read since [restartReadTimer]. The host
     *  calls it on pause, and it writes nothing in incognito mode. */
    suspend fun updateHistory()

    /** Starts the read clock again. The host calls it on resume, since pausing stopped it. */
    fun restartReadTimer()

    /** Steps a chapter. Both types resolve their own neighbour, since what is next depends on the
     *  reading order and skip settings of that type's own chapter list. */
    suspend fun previousChapter()

    suspend fun nextChapter()

    /**
     * Whether to show how far into the chapter the reader is while the chrome is hidden. Each type
     * stores its own answer, which is why the host cannot read one preference for both.
     */
    val showProgress: Flow<Boolean>

    /**
     * Whether a chapter is in flight and whether the last attempt failed. Shared because a blank
     * page with no explanation is the same defect whatever the content type, and only the session
     * knows which of its chapters is being fetched.
     */
    val loadState: Flow<ReaderLoadState>

    /**
     * Re-runs the load that failed as it ran, so a failed reload from the source is retried from the
     * source rather than from a downloaded copy. Only reachable from the failure the host raises.
     */
    fun retryLoad()

    /**
     * Loads the open chapter again where the reader is: from a downloaded copy when there is one, or from
     * the source when [fromSource], skipping that copy and whatever the session cached of the chapter.
     */
    fun reloadChapter(fromSource: Boolean)

    /**
     * Whether the open chapter is bookmarked, and the verb that flips it. Every content type has
     * chapters and every one can bookmark them, so the bar asks the session rather than reading one
     * engine's model, which is how this control ended up permanently empty for novels.
     */
    val bookmarked: Flow<Boolean>

    fun toggleBookmark()

    /**
     * The open chapter's page on the source site, or null where it has none. Null hides the web,
     * browser and share actions rather than showing them dead, which is the capability-slot rule.
     */
    val webUrl: Flow<String?>

    /** The chapter sheet's rows and its verbs, which every content type has. */
    val chapterList: ReaderChapterList

    /** Typography, or null for a type whose pages are images rather than text. */
    val textSettings: ReaderTextSettings?

    /** Continuous scrolling, or null for a type that offers no such setting. */
    val autoScroll: ReaderAutoScroll?

    /** Bionic reading, or null for a type whose pages are images and so have no words to bold. */
    val bionicReading: ReaderBionicReading?

    /** Read-aloud, or null for a type whose pages are images and so have no text to read. */
    val readAloud: ReaderReadAloud?

    /**
     * The entry's own rotation flag, a [eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation]
     * `flagValue` where 0 means follow that content type's global default. Both types store one per
     * entry, so the bar icon and the picker read it here rather than from a model.
     */
    val orientation: Flow<Int>

    fun setOrientation(flagValue: Int)

    /** Each type has its own keep-screen-on preference, and only novels offer it as a bar button. */
    val keepScreenOn: Flow<Boolean>

    fun setKeepScreenOn(enabled: Boolean)

    /**
     * Builds the viewport for this content type. Called again whenever the shape changes, for
     * instance on a reading-mode switch, and the engine destroys the previous one.
     */
    fun createViewport(host: ReaderActivity): ReaderViewport

    /**
     * Wires this session into a [host] that has just set its content view, once per Activity. A session
     * whose viewport is ready at once builds and shows it here, with whatever feeds it. The window stays
     * the host's: it shows the viewport under its insets and applies the orientation it is handed.
     */
    fun attach(host: ReaderActivity)

    /** Lets go of [viewport] before the engine destroys it, since the session can outlive the view tree. */
    fun detach(viewport: ReaderViewport)
}
