package reikai.navigation

import kotlinx.serialization.Serializable

/**
 * What a WebView page opens for besides its url: nothing, or a novel source's page, opened with that source's
 * headers, keeping the site's storage when the source is a plugin that asks for it, and offering the novel's own
 * actions when [Page.novelId] is set, a chapter's when [Page.chapterId] is too. A plugin's site is a [Page] of
 * that plugin, since a plugin's source id is its plugin id. A default of [None] keeps a manga route's encoding
 * upstream's.
 */
@Serializable
sealed interface NovelWebTarget {
    @Serializable
    data object None : NovelWebTarget

    @Serializable
    data class Page(val sourceId: String, val novelId: Long? = null, val chapterId: Long? = null) : NovelWebTarget
}
