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
 * the lock screen; the library's Lewd filter reads the same source sets at a stricter [AdultWarnings]. An LN
 * plugin answers SAFE, since its format has no adult flag, so its genres decide. The gallery signal asks
 * [GallerySources.isGallerySource] rather than testing for a metadata source, which the enhanced MangaDex
 * also is: keying on that hid every MangaDex title.
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
        adultSources = {
            adultMangaSources(entries.mapTo(mutableSetOf(), Manga::source), AdultWarnings.MIXED_OR_NSFW)
        },
        isAdult = { manga, adultSources ->
            isAdultEntry(manga.source in adultSources, sourceManager.get(manga.source)?.name, manga.genre)
        },
    )

    /** The novel entry point: the installing app's warning, which a novel source carries itself. */
    suspend fun adultNovelIdsAmong(novels: List<Novel>): Set<Long> = adultAmong(
        novels,
        Novel::id,
        adultSources = {
            adultNovelSources(novels.mapTo(mutableSetOf(), Novel::source), AdultWarnings.MIXED_OR_NSFW)
        },
        isAdult = { novel, adultSources -> isAdultEntry(novel.source in adultSources, null, novel.genre) },
    )

    /**
     * The library Lewd filter's adult manga sources. A scan that outlasts the wait counts no source, so
     * tags and names still decide and the library never hangs on it.
     */
    suspend fun libraryAdultMangaSources(sourceIds: Set<Long>): Set<Long> =
        adultMangaSources(sourceIds, AdultWarnings.NSFW_ONLY).orEmpty()

    /** The library's adult novel sources, by the same rule as [libraryAdultMangaSources]. */
    suspend fun libraryAdultNovelSources(sourceIds: Set<String>): Set<String> =
        adultNovelSources(sourceIds, AdultWarnings.NSFW_ONLY).orEmpty()

    /**
     * The sources among [sourceIds] an extension warns as one of [warnings], or that are a built-in gallery;
     * null when the extension scan outlasts [withinScanWait].
     */
    private suspend fun adultMangaSources(sourceIds: Set<Long>, warnings: AdultWarnings): Set<Long>? =
        withinScanWait {
            val warned = extensionManager.loadedExtensionsFlow.first()
                .filter { it.contentWarning in warnings.counted }
                .flatMapTo(mutableSetOf()) { extension -> extension.sources.map { it.id } }
            sourceIds.filterTo(mutableSetOf()) { it in warned || GallerySources.isGallerySource(it, sourceManager) }
        }

    /** The novel sources among [sourceIds] whose installing app warns as one of [warnings], or null as above. */
    private suspend fun adultNovelSources(sourceIds: Set<String>, warnings: AdultWarnings): Set<String>? =
        withinScanWait {
            sourceIds.filterTo(mutableSetOf()) { id ->
                novelSourceManager.getWithoutPlugins(id)?.contentWarning in warnings.counted
            }
        }

    /**
     * An expired source wait calls every entry adult, because the caller is a privacy switch and a generic
     * notification beats a leaked title.
     */
    private suspend fun <E, S> adultAmong(
        entries: List<E>,
        id: (E) -> Long,
        adultSources: suspend () -> Set<S>?,
        isAdult: suspend (E, Set<S>) -> Boolean,
    ): Set<Long> {
        val adult = adultSources() ?: return entries.mapTo(mutableSetOf(), id)
        return entries.filter { isAdult(it, adult) }.mapTo(mutableSetOf(), id)
    }
}

/**
 * The source signal reads the installed apps, which stay silent until the extension scan finishes. The wait
 * is capped because nothing flips that gate if the scan throws, so an unbounded one would hold its caller
 * forever; null means it expired.
 */
private suspend fun <T> withinScanWait(block: suspend () -> T): T? = withTimeoutOrNull(EXTENSION_SCAN_WAIT) { block() }

/** Which extension content warnings make a source adult: the one way the two adult rules differ. */
private enum class AdultWarnings(val counted: Set<ContentWarning>) {
    /** The library's Lewd filter. Mixed covers most mainstream extensions, so counting it hid most of a library. */
    NSFW_ONLY(setOf(ContentWarning.NSFW)),

    /** Notifications, a privacy switch, where a stray generic line is the safe miss. */
    MIXED_OR_NSFW(setOf(ContentWarning.MIXED, ContentWarning.NSFW)),
}

private val EXTENSION_SCAN_WAIT = 5.seconds
