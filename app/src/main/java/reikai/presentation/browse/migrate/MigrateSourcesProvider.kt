package reikai.presentation.browse.migrate

import eu.kanade.tachiyomi.ui.browse.migration.sources.MigrateSourceViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import reikai.domain.library.ContentType
import reikai.domain.source.SourceKey
import reikai.novel.source.toLangCode

/**
 * One content type's half of the migrate-from list. A provider answers about its own sources; it
 * never sorts, applies the chip or decides what the list looks like, because those describe the
 * whole list and the engine owns them.
 */
interface MigrateSourcesProvider {

    val contentType: ContentType

    /** Sources holding favourites, unsorted. Null until the first list has been produced. */
    val rows: Flow<List<BrowseMigrateRow>?>
}

/** The manga half, over Mihon's live [MigrateSourceViewModel]. */
class MangaMigrateSourcesProvider(private val model: MigrateSourceViewModel) : MigrateSourcesProvider {

    override val contentType = ContentType.MANGA

    override val rows: Flow<List<BrowseMigrateRow>?> = model.sources.map { sources ->
        sources?.map { (source, count) ->
            BrowseMigrateRow(
                key = SourceKey.Manga(source.id),
                // A source with nothing but an id left still has to be pickable.
                name = source.name.ifBlank { source.id.toString() },
                lang = source.lang,
                count = count,
                isStub = source.isStub,
                source = source,
            )
        }
    }
}

/** The light-novel half, over [MigrateNovelSourcesViewModel]. */
class NovelMigrateSourcesProvider(private val model: MigrateNovelSourcesViewModel) : MigrateSourcesProvider {

    override val contentType = ContentType.NOVELS

    override val rows: Flow<List<BrowseMigrateRow>?> = model.sources.map { sources ->
        sources?.map { source ->
            BrowseMigrateRow(
                key = SourceKey.Novel(source.id),
                name = source.name,
                // Normalised like the Sources list, so a plugin naming its language in that
                // language still renders a language name here.
                lang = source.lang.toLangCode(),
                count = source.count.toLong(),
                isStub = !source.isInstalled,
                source = source,
            )
        }
    }
}
