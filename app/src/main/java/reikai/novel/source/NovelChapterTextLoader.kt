package reikai.novel.source

import android.content.Context
import kotlinx.coroutines.flow.Flow
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.content.NovelContentConfig
import reikai.novel.content.NovelContentPipeline
import reikai.novel.content.NovelHtmlUtils
import reikai.novel.install.LnPluginInstaller
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.util.Collections

/**
 * Fetches a chapter's text and resolves the source it came from, for one reading session.
 *
 * Held per session rather than shared, because the source cache is keyed on novel id and a merged
 * session walks several novels: a session-scoped cache resolves each once and no more.
 */
class NovelChapterTextLoader(
    private val context: Context,
    private val novelRepo: NovelRepository,
    private val sourceManager: NovelSourceManager,
    private val installer: LnPluginInstaller,
    private val preferences: NovelPreferences,
    /** The downloaded copy, or null when the chapter is not on disk. */
    private val readDownloaded: (Novel, NovelChapter) -> String?,
) {

    /**
     * Emits when a setting that changes what [load] produces changes. A session caches pipeline output
     * per chapter, so without re-running it a flipped switch reaches the page only on the next open.
     */
    val settingsChanged: Flow<Unit> = NovelContentConfig.changes(preferences)

    private val sourcesByNovel: MutableMap<Long, NovelSource> =
        Collections.synchronizedMap(HashMap())

    /** The reader's retry point: a lookup never retries a plugin that failed to load, so the session
     *  runs [LnPluginInstaller.ensureLoaded] once before its first source resolve. */
    @Volatile
    private var pluginsLoaded = false

    /** The source resolved for [novelId] so far, if a chapter of it has already loaded. */
    fun cachedSource(novelId: Long): NovelSource? = sourcesByNovel[novelId]

    /**
     * Downloaded chapter: read the self-contained HTML from disk (no source, null base URL, images
     * already inlined). Otherwise resolve the chapter's source and parse live, using the source site
     * as the base URL so relative image URLs resolve.
     *
     * What comes back is pipeline output, never raw source markup, so a renderer must not process it
     * again. The target follows the rendering mode, which decides whether embedded CSS and JS survive.
     */
    suspend fun load(chapter: NovelChapter, fromSource: Boolean = false): Pair<String, String?> {
        val (raw, baseUrl) = fetch(chapter, fromSource)
        val config = NovelContentConfig.from(preferences, chapterUrl = chapter.url, chapterName = chapter.name)
        val processed = NovelContentPipeline.process(raw, config)
        // A plain-text chapter leaves the pipeline unescaped and unsanitised, because a text renderer
        // takes it verbatim. Both readers are HTML sinks, so it is escaped here or a `.txt` chapter's
        // markup becomes live document.
        val html = when {
            processed.isPlainText -> NovelHtmlUtils.plainTextToHtml(processed.text)
            // Here rather than in a renderer, so both draw the same escaped text from one switch.
            config.showRawHtml -> NovelHtmlUtils.htmlAsText(processed.text)
            else -> processed.text
        }
        return html to baseUrl
    }

    /** The downloaded copy when there is one, unless [fromSource] asks the source regardless. */
    private suspend fun fetch(chapter: NovelChapter, fromSource: Boolean): Pair<String, String?> {
        val novel = novelRepo.getById(chapter.novelId)
        if (novel != null && !fromSource) readDownloaded(novel, chapter)?.let { return it to null }
        val src = resolveSource(chapter.novelId)
        // A blank page reads as the reader failing to draw, so an empty answer fails the load instead.
        val text = src.parseChapter(chapter.url).ifBlank { throw EmptyChapterException() }
        return text to src.site.ifBlank { null }
    }

    /**
     * Resolve (and cache) the source owning [forNovelId]. Each chapter in a merged session resolves
     * by its own `novelId`, so prev/next can cross source boundaries.
     */
    suspend fun resolveSource(forNovelId: Long): NovelSource {
        sourcesByNovel[forNovelId]?.let { return it }
        if (!pluginsLoaded) {
            runCatchingCancellable { installer.ensureLoaded() }.onSuccess { pluginsLoaded = true }
        }
        val sourceId = novelRepo.getById(forNovelId)?.source ?: error("Novel not found")
        val resolved = sourceManager.get(sourceId)
            ?: error(context.stringResource(MR.strings.source_not_installed, sourceManager.nameOf(sourceId)))
        sourcesByNovel[forNovelId] = resolved
        return resolved
    }
}

/** The source answered a chapter with no text. */
class EmptyChapterException : Exception()
