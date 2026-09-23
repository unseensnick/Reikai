package reikai.domain.source

import kotlinx.serialization.Serializable

/**
 * Icons for a novel app whose own icon shows nothing, as every IReader app's does: the icon its store
 * lists for it, and the icon any novel store or plugin repo lists for a site. Stored, since the listings
 * come from the network and an icon is wanted on a cold start.
 */
@Serializable
data class NovelIconHints(
    val packages: Map<String, String> = emptyMap(),
    val sites: Map<String, String> = emptyMap(),
) {

    /** These hints with [packages] and the icons of the sites in [siteIcons] added, newer winning. */
    fun plus(packages: Map<String, String>, siteIcons: List<Pair<String?, String>>) = NovelIconHints(
        packages = this.packages + packages,
        sites = sites + siteIcons.mapNotNull { (site, icon) -> siteHost(site)?.let { it to icon } },
    )

    /** The icons to try for [pkgName], in order: its store's, then any listed for a site it runs on. */
    fun candidatesFor(pkgName: String, siteUrls: List<String?>): List<String> =
        (listOfNotNull(packages[pkgName]) + siteUrls.mapNotNull { siteHost(it)?.let(sites::get) }).distinct()
}
