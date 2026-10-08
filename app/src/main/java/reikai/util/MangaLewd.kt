package reikai.util

/**
 * The "is this adult content?" rule both the library's Lewd filter and the notification check run, on manga
 * and novels: a source known to be adult ([adultSource], whose warning tiers differ per caller, see
 * AdultWarnings), an adult source name, or an adult genre tag. The name and tag heuristic is ported from
 * Komikku's `LewdMangaChecker`, without its delegated-source branches or its "mature" tag, which
 * mainstream sources put on series that are not adult. The name list is manga sites, so novels pass null.
 */
fun isAdultEntry(adultSource: Boolean, sourceName: String?, genres: List<String>?): Boolean =
    adultSource || (sourceName != null && isHentaiSource(sourceName)) || hasLewdGenre(genres)

private fun hasLewdGenre(genres: List<String>?): Boolean = genres.orEmpty().any(::isHentaiTag)

private fun isHentaiTag(tag: String): Boolean {
    return tag.contains("hentai", true) ||
        tag.contains("adult", true) ||
        tag.contains("smut", true) ||
        tag.contains("lewd", true) ||
        tag.contains("nsfw", true) ||
        tag.contains("erotica", true) ||
        tag.contains("pornographic", true) ||
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
