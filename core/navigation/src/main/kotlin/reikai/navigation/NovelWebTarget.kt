package reikai.navigation

import kotlinx.serialization.Serializable

/**
 * What a WebView page opens for besides its url: nothing, a light-novel plugin's site (whose storage is kept
 * for it), or a novel source's page (opened with that source's headers, and offering the novel's own actions
 * when [Page.novelId] is set, a chapter's when [Page.chapterId] is too). A default of [None] keeps a manga
 * route's encoding upstream's.
 */
@Serializable
sealed interface NovelWebTarget {
    @Serializable
    data object None : NovelWebTarget

    @Serializable
    data class Plugin(val pluginId: String) : NovelWebTarget

    @Serializable
    data class Page(val sourceId: String, val novelId: Long? = null, val chapterId: Long? = null) : NovelWebTarget
}
