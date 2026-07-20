package reikai.util

import tachiyomi.domain.manga.model.Manga

/**
 * Heuristic "is this adult content?" check for the library lewd filter.
 *
 * Ported from Komikku's `exh.util.LewdMangaChecker` (`Manga.isLewd`), re-typed onto Mihon's
 * immutable [Manga]. The E-Hentai/nHentai/delegated-source branches of the original are
 * deliberately omitted: they hang off Komikku's EXH subsystem, which Reikai does not have. The
 * precise adult-source handling is deferred to a later sprint; this keeps the portable core
 * (genre-tag + source-name heuristics), which already recognizes the common adult sources by
 * name when installed as third-party extensions.
 *
 * Pure function: the caller resolves [sourceName] (no Injekt here) so this stays unit-testable.
 */
fun Manga.isLewd(sourceName: String?): Boolean {
    return (sourceName != null && isHentaiSource(sourceName)) ||
        hasLewdGenre(genre)
}

/**
 * The genre-tag half of the lewd heuristic, shared with the novel library. Novel sources carry no nsfw
 * flag (unlike Mihon manga extensions), so a novel's only lewd signal is its adult genre tags; this is the
 * whole novel check, and the source-name half stays manga-only.
 */
fun hasLewdGenre(genres: List<String>?): Boolean = genres.orEmpty().any(::isHentaiTag)

private fun isHentaiTag(tag: String): Boolean {
    return tag.contains("hentai", true) ||
        tag.contains("adult", true) ||
        tag.contains("smut", true) ||
        tag.contains("lewd", true) ||
        tag.contains("nsfw", true) ||
        tag.contains("erotica", true) ||
        tag.contains("pornographic", true) ||
        tag.contains("mature", true) ||
        tag.contains("18+", true)
}

private fun isHentaiSource(source: String): Boolean {
    return source.contains("allporncomic", true) ||
        source.contains("hentai cafe", true) ||
        source.contains("hentai2read", true) ||
        source.contains("hentaifox", true) ||
        source.contains("hentainexus", true) ||
        source.contains("manhwahentai.me", true) ||
        source.contains("milftoon", true) ||
        source.contains("myhentaicomics", true) ||
        source.contains("myhentaigallery", true) ||
        source.contains("ninehentai", true) ||
        source.contains("pururin", true) ||
        source.contains("simply hentai", true) ||
        source.contains("tsumino", true) ||
        source.contains("8muses", true) ||
        source.contains("hbrowse", true) ||
        source.contains("nhentai", true) ||
        source.contains("erofus", true) ||
        source.contains("luscious", true) ||
        source.contains("doujins", true) ||
        source.contains("multporn", true) ||
        source.contains("vcp", true) ||
        source.contains("vmp", true) ||
        source.contains("hentai", true)
}
