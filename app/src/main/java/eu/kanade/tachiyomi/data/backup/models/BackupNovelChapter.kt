package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import yokai.domain.novel.models.NovelChapter

/**
 * Protobuf shape for novel chapters in backups. Mirrors [BackupChapter] field-for-field where
 * the columns match; replaces `lastPageRead` / `pagesLeft` with `lastTextProgress` to match the
 * `novel_chapters.last_text_progress` column (0..10000 hundredths-of-a-percent for mid-chapter
 * resume in the continuous-scroll text reader).
 */
@Serializable
data class BackupNovelChapter(
    @ProtoNumber(1) var url: String,
    @ProtoNumber(2) var name: String,
    @ProtoNumber(3) var read: Boolean = false,
    @ProtoNumber(4) var bookmark: Boolean = false,
    @ProtoNumber(5) var lastTextProgress: Int = 0,
    @ProtoNumber(6) var dateFetch: Long = 0,
    @ProtoNumber(7) var dateUpload: Long = 0,
    @ProtoNumber(8) var chapterNumber: Float = 0F,
    @ProtoNumber(9) var sourceOrder: Long = 0,
) {
    fun toNovelChapter(novelId: Long): NovelChapter = NovelChapter(
        id = null,
        novelId = novelId,
        url = url,
        name = name,
        read = read,
        bookmark = bookmark,
        lastTextProgress = lastTextProgress,
        chapterNumber = chapterNumber,
        sourceOrder = sourceOrder,
        dateFetch = dateFetch,
        dateUpload = dateUpload,
    )

    companion object {
        fun copyFrom(chapter: NovelChapter): BackupNovelChapter = BackupNovelChapter(
            url = chapter.url,
            name = chapter.name,
            read = chapter.read,
            bookmark = chapter.bookmark,
            lastTextProgress = chapter.lastTextProgress,
            chapterNumber = chapter.chapterNumber,
            sourceOrder = chapter.sourceOrder,
            dateFetch = chapter.dateFetch,
            dateUpload = chapter.dateUpload,
        )
    }
}
