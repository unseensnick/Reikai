package reikai.domain.manga

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.extension.ExtensionManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import reikai.domain.merge.ChapterMatchKeys
import reikai.util.isLewd
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Duration.Companion.seconds

/**
 * RK: is a manga adult content, for hiding its title + cover from notifications and the lock screen.
 * Any one signal qualifies: an NSFW-flagged extension, a built-in gallery source (which has no
 * extension to carry that flag), or the [isLewd] genre-tag and source-name heuristic.
 *
 * The gallery signal asks [ChapterMatchKeys.isGallerySource] rather than testing for a metadata
 * source, which the enhanced MangaDex also is: keying on that hid every MangaDex title.
 */
@Inject
class AdultContentChecker(
    private val extensionManager: ExtensionManager,
    private val sourceManager: SourceManager,
) {
    /**
     * The adult entries among [entries], resolved in one pass.
     *
     * Suspends because the NSFW-extension signal reads the installed list, which stays silent until
     * the extension scan finishes. The wait is capped: nothing flips that gate if the scan throws,
     * so an unbounded one would hold the notification forever. An expired wait calls every entry
     * adult, because the caller is a privacy switch and a generic notification beats a leaked title.
     */
    suspend fun adultIdsAmong(entries: List<Manga>): Set<Long> {
        val nsfwSourceIds = withTimeoutOrNull(EXTENSION_SCAN_WAIT) {
            extensionManager.installedExtensionsFlow.first()
                .filter { it.isNsfw }
                .flatMapTo(mutableSetOf()) { extension -> extension.sources.map { it.id } }
        } ?: return entries.mapTo(mutableSetOf()) { it.id }

        return entries.filter { isAdult(it, nsfwSourceIds) }.mapTo(mutableSetOf()) { it.id }
    }

    private suspend fun isAdult(manga: Manga, nsfwSourceIds: Set<Long>): Boolean =
        ChapterMatchKeys.isGallerySource(manga.source, sourceManager) ||
            manga.source in nsfwSourceIds ||
            manga.isLewd(sourceManager.get(manga.source)?.name)
}

private val EXTENSION_SCAN_WAIT = 5.seconds
