package reikai.domain.novel

import kotlinx.serialization.Serializable

/**
 * What is known about a light-novel source without running its plugin, persisted in
 * [NovelPreferences.seenNovelSources] by plugin id. Written on every load or install and never pruned
 * on uninstall, so a novel whose plugin was removed still shows a real name and icon in the Browse
 * migration list (the twin of manga's stub-source name table); a source never seen here falls back to
 * its raw id. [site] and [imageHeaders] are how its images are fetched, which a cover needs before any
 * plugin has loaded (`NovelImageRequests`).
 */
@Serializable
data class LnSourceIdentity(
    val name: String,
    val iconUrl: String? = null,
    val lang: String? = null,
    val site: String? = null,
    val imageHeaders: Map<String, String> = emptyMap(),
)
