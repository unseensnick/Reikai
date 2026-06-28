package reikai.domain.novel

import kotlinx.serialization.Serializable

/**
 * Last-known display identity for a light-novel source, persisted in [NovelPreferences.seenNovelSources]
 * keyed by the plugin id. Written whenever a source is loaded/installed and never pruned on uninstall,
 * so a favorited novel whose plugin was later removed still resolves to a real name + icon in the
 * Browse migration list (the novel twin of manga's persisted stub-source name table). When even this
 * cache misses (a source never installed on this device, e.g. a backup restore), callers fall back to
 * the raw plugin id.
 */
@Serializable
data class LnSourceIdentity(
    val name: String,
    val iconUrl: String? = null,
    val lang: String? = null,
)
