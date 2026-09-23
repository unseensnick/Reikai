package reikai.domain.novel

import kotlinx.serialization.Serializable

/**
 * What is known about a light-novel source without running it, persisted in
 * [NovelPreferences.seenNovelSources] by source id. Written on every load or install and never pruned
 * on uninstall, so a novel whose source was removed still shows a real name in the Browse migration list,
 * as manga's stub sources keep theirs; a source never seen here falls back to its raw id.
 * [site] and [imageHeaders] are how a plugin's images are fetched, which a cover needs before any plugin
 * has loaded (`NovelImageRequests`).
 */
@Serializable
data class LnSourceIdentity(
    val name: String,
    val iconUrl: String? = null,
    val lang: String? = null,
    val site: String? = null,
    val imageHeaders: Map<String, String> = emptyMap(),
)
