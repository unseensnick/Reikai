package reikai.presentation.browse.components

import reikai.data.coil.NovelCover
import reikai.domain.novel.model.NovelWithChapterCount
import tachiyomi.domain.manga.model.MangaWithChapterCount

/**
 * One card in the shared duplicate dialog. [coverModel] is a coil model (a `Manga` or a [NovelCover]),
 * so each content type feeds its own object and nothing here branches on type. Author and artist are
 * kept raw: the card applies the blank and same-name rules once, for both types.
 */
data class EntryDuplicateCardUi(
    val id: Long,
    val coverModel: Any,
    val title: String,
    val author: String?,
    val artist: String?,
    val status: Long,
    val source: EntrySourceLabel,
    val chapterCount: Long,
)

/**
 * A duplicate's source, as far as the app can resolve it. [Missing] means its extension or plugin is
 * not installed, so the name is only what was stored (a manga stub's name, a novel's raw source key)
 * and the card warns about it.
 */
sealed interface EntrySourceLabel {
    val name: String

    data class Installed(override val name: String) : EntrySourceLabel

    data class Missing(override val name: String) : EntrySourceLabel
}

/**
 * Both mappers take the whole label map their dialog was given, keyed by source. A source missing from
 * it cannot happen (the map is built from these same rows), so the fallback only keeps a future caller
 * from crashing, and shows the raw source key it would otherwise have nothing to print.
 */
fun MangaWithChapterCount.toDuplicateCard(sourceLabels: Map<Long, EntrySourceLabel>) = EntryDuplicateCardUi(
    id = manga.id,
    coverModel = manga,
    title = manga.title,
    author = manga.author,
    artist = manga.artist,
    status = manga.status,
    source = sourceLabels[manga.source] ?: EntrySourceLabel.Missing(manga.source.toString()),
    chapterCount = chapterCount,
)

fun NovelWithChapterCount.toDuplicateCard(
    sourceLabels: Map<String, EntrySourceLabel>,
    sourceSites: Map<String, String?>,
) = EntryDuplicateCardUi(
    id = novel.id,
    // Duplicates are library rows by definition, so the cover fetcher can take the favorite path.
    coverModel = NovelCover(
        url = novel.thumbnailUrl,
        site = sourceSites[novel.source],
        isNovelFavorite = true,
        lastModified = novel.coverLastModified,
        novelId = novel.id,
    ),
    title = novel.title,
    author = novel.author,
    artist = novel.artist,
    status = novel.status,
    source = sourceLabels[novel.source] ?: EntrySourceLabel.Missing(novel.source),
    chapterCount = chapterCount,
)
