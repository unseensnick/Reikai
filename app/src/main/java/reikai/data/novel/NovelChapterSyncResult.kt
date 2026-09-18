package reikai.data.novel

import reikai.domain.novel.model.NovelChapter

/**
 * What [syncChaptersWithNovelSource] did. [newChapters] leaves out what
 * [reikai.domain.chapter.chapterArrivals] flags, as the manga sync's return does.
 */
data class NovelChapterSyncResult(val newChapters: List<NovelChapter>, val changed: Boolean) {
    operator fun plus(other: NovelChapterSyncResult) =
        NovelChapterSyncResult(newChapters + other.newChapters, changed || other.changed)

    companion object {
        val UNCHANGED = NovelChapterSyncResult(emptyList(), changed = false)
    }
}
