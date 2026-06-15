package reikai.domain.novel.model

import androidx.compose.runtime.Immutable
import reikai.data.coil.NovelCover

/**
 * One light-novel "recent update" row, the novel twin of
 * [tachiyomi.domain.updates.model.UpdatesWithRelations]: a chapter of a favorited novel that was
 * fetched after the novel was added. [coverData] is the novel coil model (the cover loads from the
 * library [eu.kanade.tachiyomi.data.cache.CoverCache] for favorites).
 */
@Immutable
data class NovelUpdateWithRelations(
    val novelId: Long,
    val novelTitle: String,
    val chapterId: Long,
    val chapterName: String,
    val chapterUrl: String,
    val read: Boolean,
    val bookmark: Boolean,
    val lastTextProgress: Long,
    val source: String,
    val dateFetch: Long,
    val isDownloaded: Boolean,
    val coverData: NovelCover,
)
