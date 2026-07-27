package exh.source

// Stock adult extensions / sources that Reikai hides while its built-in equivalents are active.
// The stock E-Hentai extension registers its sources under the same ids as our built-in
// E-Hentai / ExHentai sources, so leaving it installed would shadow (in the source map) and
// duplicate (in the extensions list) the built-in ones. Mirrors Komikku's BlacklistedSources.
object BlacklistedSources {
    // Skipped from the source map when the hentai gate is on (built-in EH/ExH registers under these
    // ids). Covers every E-Hentai / ExHentai language id, since a stock source could carry any.
    val BLACKLISTED_EXT_SOURCES = eHentaiSourceIds

    // Hidden from the installed / available / untrusted extension lists when the hentai gate is on.
    val BLACKLISTED_EXTENSIONS = arrayOf(EH_PACKAGE)
}
