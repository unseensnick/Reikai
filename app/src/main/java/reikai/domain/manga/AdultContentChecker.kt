package reikai.domain.manga

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.extension.ExtensionManager
import reikai.domain.merge.ChapterMatchKeys
import reikai.util.isLewd
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

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
    fun isAdult(manga: Manga): Boolean =
        ChapterMatchKeys.isGallerySource(manga.source, sourceManager) ||
            isNsfwExtensionSource(manga.source) ||
            manga.isLewd(sourceManager.get(manga.source)?.name)

    private fun isNsfwExtensionSource(sourceId: Long): Boolean =
        extensionManager.installedExtensionsFlow.value.any { extension ->
            extension.isNsfw && extension.sources.any { it.id == sourceId }
        }
}
