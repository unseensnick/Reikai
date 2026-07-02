package reikai.domain.manga

import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.online.MetadataSource
import exh.source.getMainSource
import reikai.util.isLewd
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * RK: is a manga adult content, for hiding its title + cover from notifications and the lock screen.
 * Any one signal qualifies:
 *  1. its source's extension is flagged NSFW (Keiyoushi `tachiyomi.extension.nsfw`) - covers hentai
 *     extensions;
 *  2. its source is a built-in metadata/gallery source (EH / nhentai.net / pururin / wrappers), which
 *     has no extension to carry the flag;
 *  3. the [isLewd] genre-tag / source-name heuristic, so nothing adult slips through.
 */
class AdultContentChecker(
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
) {
    fun isAdult(manga: Manga): Boolean {
        val source = sourceManager.get(manga.source)
        return source?.getMainSource<MetadataSource<*, *>>() != null ||
            isNsfwExtensionSource(manga.source) ||
            manga.isLewd(source?.name)
    }

    private fun isNsfwExtensionSource(sourceId: Long): Boolean =
        extensionManager.installedExtensionsFlow.value.any { extension ->
            extension.isNsfw && extension.sources.any { it.id == sourceId }
        }
}
