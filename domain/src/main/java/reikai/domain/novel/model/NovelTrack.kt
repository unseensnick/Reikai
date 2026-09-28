package reikai.domain.novel.model

import java.io.Serializable

/**
 * Domain mirror of the `novel_tracks` table, the novel twin of [tachiyomi.domain.track.model.Track].
 * Disjoint from the manga track because novel ids and manga ids share the same INTEGER PRIMARY KEY
 * space across separate tables.
 */
data class NovelTrack(
    val id: Long,
    val novelId: Long,
    val trackerId: Long,
    val remoteId: Long,
    val libraryId: Long?,
    val title: String,
    val lastChapterRead: Double,
    val totalChapters: Long,
    val status: Long,
    val score: Double,
    val remoteUrl: String,
    val startDate: Long,
    val finishDate: Long,
    val private: Boolean,
) : Serializable
