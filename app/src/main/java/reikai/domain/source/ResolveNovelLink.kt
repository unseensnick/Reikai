package reikai.domain.source

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.cache.CoverCache
import reikai.data.novel.insertOpenedNovel
import reikai.data.novel.refreshNovelFromSource
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.novel.host.SourceNovel
import reikai.novel.source.NovelLink
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.util.runCatchingCancellable
import tachiyomi.domain.library.service.LibraryPreferences

/** What a shared link opens among novels: a novel by its source and path, or a stored chapter. */
sealed interface NovelLinkTarget {
    data class Novel(val sourceId: String, val url: String) : NovelLinkTarget
    data class Chapter(val novelId: Long, val chapterId: Long) : NovelLinkTarget
}

/**
 * Reads a shared link as a novel, one tier at a time so the deep-link screen can put the manga tiers
 * between them: a source's own link reading, then a stored row, then a checked guess from the address.
 * Text that is no link returns before any source is loaded. [ResolveMangaLink] is the manga twin, and
 * both keep their rules in [SharedLink].
 */
class ResolveNovelLink(
    private val loadedSources: suspend () -> List<NovelSource>,
    private val novelRepository: NovelRepository,
    private val novelChapterRepository: NovelChapterRepository,
    private val libraryPreferences: LibraryPreferences,
    private val coverCache: CoverCache,
) {

    // A novel screen opening is the sanctioned retry point for a plugin that failed to load.
    @Inject
    constructor(
        sourceManager: NovelSourceManager,
        novelRepository: NovelRepository,
        novelChapterRepository: NovelChapterRepository,
        libraryPreferences: LibraryPreferences,
        coverCache: CoverCache,
    ) : this(
        {
            sourceManager.ensureLoaded()
            sourceManager.getAll()
        },
        novelRepository,
        novelChapterRepository,
        libraryPreferences,
        coverCache,
    )

    private var loaded: List<NovelSource>? = null

    // Loaded once for all three tiers, since each load retries every plugin that failed.
    private suspend fun sources() = loaded ?: loadedSources().also { loaded = it }

    suspend fun byHook(text: String): NovelLinkTarget? {
        SharedLink.parse(text) ?: return null
        for (source in sources()) {
            val link = source.links?.resolve(text.trim()) ?: continue
            return when (link) {
                is NovelLink.Novel -> NovelLinkTarget.Novel(source.id, link.path)
                is NovelLink.Chapter -> chapterTarget(source, link)
            }
        }
        return null
    }

    suspend fun byStoredRow(text: String): NovelLinkTarget? =
        single(text)?.let { match -> match.stored?.let { NovelLinkTarget.Novel(match.source.id, it) } }

    suspend fun byGuess(text: String): NovelLinkTarget? {
        // A stored match names nothing, so it is never guessed again.
        val match = single(text) ?: return null
        val first = match.named.firstOrNull() ?: return null
        val source = match.source
        val parsed = runCatchingCancellable { source.parseNovel(first) }.getOrNull() ?: return null
        if (!parsed.isANovel()) return null
        val path = SharedLink.spelling(match.named, parsed.chapters.orEmpty().map { it.path }) ?: return null
        val isBelow = SharedLink.isBelowTheSameEntry(path, parsed.name.orEmpty()) { parent ->
            runCatchingCancellable { source.parseNovel(parent) }.getOrNull()?.name
        }
        if (isBelow) return null
        insertOpenedNovel(
            parsed.copy(path = path),
            source.id,
            novelRepository,
            novelChapterRepository,
            libraryPreferences,
        ) ?: return null
        return NovelLinkTarget.Novel(source.id, path)
    }

    // A novel's stored value is the spelling its row carries, which the novel screen opens by.
    private suspend fun single(text: String) = SharedLink.parse(text)?.matchOne(
        sources(),
        siteOf = { it.site },
        storedAt = { source, url -> url.takeIf { novelRepository.getByUrlAndSource(url, source.id) != null } },
        webUrl = { source, path -> source.webUrl(path, true) },
    )

    // A page that is not a novel's still parses on many sites; a name and something to read is the bar.
    // Plugins fill a missing name with a placeholder (novelhall.ts `|| 'Untitled'`).
    private fun SourceNovel.isANovel() =
        !name.isNullOrBlank() && name !in PLACEHOLDER_NAMES && (!chapters.isNullOrEmpty() || totalPages > 1)

    private suspend fun chapterTarget(source: NovelSource, link: NovelLink.Chapter): NovelLinkTarget? {
        val novel = novelRepository.getByUrlAndSource(link.novelPath, source.id)
            ?: runCatchingCancellable { opened(source, link.novelPath) }.getOrNull()
            ?: return null
        val chapter = novelChapterRepository.getByUrlAndNovelId(link.chapterPath, novel.id)
            ?: runCatchingCancellable {
                refreshNovelFromSource(
                    novel,
                    source,
                    novelChapterRepository,
                    novelRepository,
                    libraryPreferences,
                    coverCache,
                )
                novelChapterRepository.getByUrlAndNovelId(link.chapterPath, novel.id)
            }.getOrNull()
        return chapter?.let { NovelLinkTarget.Chapter(novel.id, it.id) } ?: NovelLinkTarget.Novel(source.id, novel.url)
    }

    private suspend fun opened(source: NovelSource, path: String): Novel? =
        insertOpenedNovel(
            source.parseNovel(path),
            source.id,
            novelRepository,
            novelChapterRepository,
            libraryPreferences,
        )

    private companion object {
        val PLACEHOLDER_NAMES = setOf("Untitled", "No Title Found")
    }
}
