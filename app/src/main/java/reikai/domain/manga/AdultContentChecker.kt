package reikai.domain.manga

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.extension.ExtensionManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import mihon.domain.extension.model.ContentWarning
import reikai.domain.novel.isLewd
import reikai.domain.novel.model.Novel
import reikai.novel.source.NovelSourceManager
import reikai.util.isLewd
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Duration.Companion.seconds

/**
 * RK: is a manga or novel adult content, for hiding its title + cover from notifications and the lock
 * screen. Any one signal qualifies: an extension warned as mixed or 18+, a built-in gallery source (which
 * has no extension to carry that warning), or the genre-tag heuristic, which for manga also reads the
 * source name. An LN plugin answers SAFE, since its format has no adult flag, so its genres decide.
 * The gallery signal asks [GallerySources.isGallerySource] rather than testing for a metadata
 * source, which the enhanced MangaDex also is: keying on that hid every MangaDex title.
 */
@Inject
class AdultContentChecker(
    private val extensionManager: ExtensionManager,
    private val sourceManager: SourceManager,
    private val novelSourceManager: NovelSourceManager,
) {
    /** The adult entries among [entries], resolved in one pass. */
    suspend fun adultIdsAmong(entries: List<Manga>): Set<Long> = adultAmong(
        entries,
        Manga::id,
        nsfwSources = {
            extensionManager.loadedExtensionsFlow.first()
                .filter { it.contentWarning != ContentWarning.SAFE }
                .flatMapTo(mutableSetOf()) { extension -> extension.sources.map { it.id } }
        },
        isAdult = { manga, nsfwSourceIds ->
            GallerySources.isGallerySource(manga.source, sourceManager) ||
                manga.source in nsfwSourceIds ||
                manga.isLewd(sourceManager.get(manga.source)?.name)
        },
    )

    /** The novel entry point: the installing app's warning, which a novel source carries itself. */
    suspend fun adultNovelIdsAmong(novels: List<Novel>): Set<Long> = adultAmong(
        novels,
        Novel::id,
        nsfwSources = {
            novels.mapTo(mutableSetOf(), Novel::source).filterTo(mutableSetOf()) { id ->
                novelSourceManager.getWithoutPlugins(id)?.contentWarning.let { it != null && it != ContentWarning.SAFE }
            }
        },
        isAdult = { novel, nsfwSourceIds -> novel.source in nsfwSourceIds || novel.isLewd() },
    )

    /**
     * Suspends because the extension signal reads the installed apps, which stay silent until the
     * extension scan finishes. The wait is capped: nothing flips that gate if the scan throws, so an
     * unbounded one would hold the notification forever. An expired wait calls every entry adult,
     * because the caller is a privacy switch and a generic notification beats a leaked title.
     */
    private suspend fun <E, S> adultAmong(
        entries: List<E>,
        id: (E) -> Long,
        nsfwSources: suspend () -> Set<S>,
        isAdult: suspend (E, Set<S>) -> Boolean,
    ): Set<Long> {
        val nsfw = withTimeoutOrNull(EXTENSION_SCAN_WAIT) { nsfwSources() } ?: return entries.mapTo(mutableSetOf(), id)
        return entries.filter { isAdult(it, nsfw) }.mapTo(mutableSetOf(), id)
    }
}

private val EXTENSION_SCAN_WAIT = 5.seconds
