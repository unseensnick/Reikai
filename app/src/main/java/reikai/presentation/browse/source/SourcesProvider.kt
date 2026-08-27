package reikai.presentation.browse.source

import eu.kanade.tachiyomi.ui.browse.source.SourcesViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import reikai.domain.library.ContentType
import reikai.domain.source.SourceKey
import reikai.novel.source.NovelSource
import reikai.novel.source.langCode
import tachiyomi.domain.source.model.Pin
import tachiyomi.domain.source.model.Source
import tachiyomi.source.local.isLocal

/**
 * One content type's half of the Sources list. A provider answers about its own sources and acts on
 * them; it never sections, filters by chip or decides what the list looks like, because those
 * describe the whole list and the engine owns them.
 */
interface SourcesProvider {

    val contentType: ContentType

    /** Enabled sources, ungrouped. Null until the first list has been produced. */
    val rows: Flow<List<BrowseSourceRow>?>

    fun togglePin(row: BrowseSourceRow)

    fun toggleDisable(row: BrowseSourceRow)

    /** Whether [row] can be hidden from the list at all. */
    fun canDisable(row: BrowseSourceRow): Boolean
}

/**
 * The manga half, over Mihon's live [SourcesViewModel].
 *
 * The last-used source arrives as a second copy of its row carrying `isUsedLast`, which is how
 * upstream makes it appear under Last used as well as in its own section; the engine sections on
 * that flag rather than re-deriving it.
 */
class MangaSourcesProvider(private val model: SourcesViewModel) : SourcesProvider {

    override val contentType = ContentType.MANGA

    override val rows: Flow<List<BrowseSourceRow>?> = model.sources.map { sources ->
        sources?.map { source ->
            BrowseSourceRow(
                key = SourceKey.Manga(source.id),
                name = source.name,
                lang = source.lang,
                isPinned = Pin.Actual in source.pin,
                isUsedLast = source.isUsedLast,
                source = source,
            )
        }
    }

    override fun togglePin(row: BrowseSourceRow) = model.togglePin(row.source as Source)

    override fun toggleDisable(row: BrowseSourceRow) = model.toggleSource(row.source as Source)

    // The local source is always available and has nothing to hide behind.
    override fun canDisable(row: BrowseSourceRow) = !(row.source as Source).isLocal()
}

/** The light-novel half, over [NovelSourcesViewModel]. */
class NovelSourcesProvider(private val model: NovelSourcesViewModel) : SourcesProvider {

    override val contentType = ContentType.NOVELS

    override val rows: Flow<List<BrowseSourceRow>?> = model.sources.map { entries ->
        entries?.map { (source, isPinned, isUsedLast) ->
            BrowseSourceRow(
                key = SourceKey.Novel(source.id),
                name = source.name,
                // Normalised to a code so a plugin declaring "Spanish" and one declaring "es" land
                // in the same section, and in the same one as the manga sources.
                lang = source.langCode(),
                isPinned = isPinned,
                isUsedLast = isUsedLast,
                source = source,
            )
        }
    }

    override fun togglePin(row: BrowseSourceRow) = model.togglePin((row.source as NovelSource).id)

    override fun toggleDisable(row: BrowseSourceRow) = model.toggleDisable((row.source as NovelSource).id)

    override fun canDisable(row: BrowseSourceRow) = true
}
