package reikai.domain.manga

import eu.kanade.tachiyomi.extension.ExtensionManager
import reikai.domain.merge.ChapterMatchKeys
import reikai.util.isLewd
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * RK: is a manga adult content, for hiding its title + cover from notifications and the lock screen.
 * Any one signal qualifies: an NSFW-flagged extension, a built-in gallery source (which has no
 * extension to carry that flag), or the [isLewd] genre-tag and source-name heuristic.
 *
 * The gallery signal asks [ChapterMatchKeys.isGallerySource] rather than testing for a metadata
 * source, which the enhanced MangaDex also is: keying on that hid every MangaDex title.
 */
class AdultContentChecker(
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
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
