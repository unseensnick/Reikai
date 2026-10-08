package reikai.domain.manga

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.extension.ExtensionManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import mihon.domain.extension.model.ContentWarning
import reikai.domain.novel.model.Novel
import reikai.novel.source.NovelSourceManager
import reikai.util.isAdultEntry
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Duration.Companion.seconds

/**
 * Is a manga or novel adult content, by [isAdultEntry], for hiding its title + cover from notifications and
 * the lock screen; the library's Lewd filter asks the same source sets. An LN plugin answers SAFE, since its
 * format has no adult flag, so its genres decide. The gallery signal asks [GallerySources.isGallerySource]
 * rather than testing for a metadata source, which the enhanced MangaDex also is: keying on that hid every
 * MangaDex title.
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
        adultSources = { adultMangaSources(entries.mapTo(mutableSetOf(), Manga::source)) },
        isAdult = { manga, adultSources ->
            isAdultEntry(manga.source in adultSources, sourceManager.get(manga.source)?.name, manga.genre)
        },
    )

    /** The novel entry point: the installing app's warning, which a novel source carries itself. */
    suspend fun adultNovelIdsAmong(novels: List<Novel>): Set<Long> = adultAmong(
        novels,
        Novel::id,
        adultSources = { adultNovelSources(novels.mapTo(mutableSetOf(), Novel::source)) },
        isAdult = { novel, adultSources -> isAdultEntry(novel.source in adultSources, null, novel.genre) },
    )

    /** The sources among [sourceIds] an extension warns as mixed or 18+, or that are a built-in gallery. */
    suspend fun adultMangaSources(sourceIds: Set<Long>): Set<Long> {
        val warned = extensionManager.loadedExtensionsFlow.first()
            .filter { it.contentWarning != ContentWarning.SAFE }
            .flatMapTo(mutableSetOf()) { extension -> extension.sources.map { it.id } }
        return sourceIds.filterTo(mutableSetOf()) { it in warned || GallerySources.isGallerySource(it, sourceManager) }
    }

    /** The novel sources among [sourceIds] whose installing app warns as mixed or 18+. */
    suspend fun adultNovelSources(sourceIds: Set<String>): Set<String> = sourceIds.filterTo(mutableSetOf()) { id ->
        novelSourceManager.getWithoutPlugins(id)?.contentWarning.let { it != null && it != ContentWarning.SAFE }
    }

    /**
     * Suspends because the extension signal reads the installed apps, which stay silent until the
     * extension scan finishes. The wait is capped: nothing flips that gate if the scan throws, so an
     * unbounded one would hold the notification forever. An expired wait calls every entry adult,
     * because the caller is a privacy switch and a generic notification beats a leaked title.
     */
    private suspend fun <E, S> adultAmong(
        entries: List<E>,
        id: (E) -> Long,
        adultSources: suspend () -> Set<S>,
        isAdult: suspend (E, Set<S>) -> Boolean,
    ): Set<Long> {
        val adult =
            withTimeoutOrNull(EXTENSION_SCAN_WAIT) { adultSources() } ?: return entries.mapTo(mutableSetOf(), id)
        return entries.filter { isAdult(it, adult) }.mapTo(mutableSetOf(), id)
    }
}

private val EXTENSION_SCAN_WAIT = 5.seconds
