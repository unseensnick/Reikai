package reikai.data.novel

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.host.ChapterItem
import reikai.novel.host.NovelTextSanitizer
import reikai.novel.host.SourceNovel
import tachiyomi.i18n.MR

/**
 * Status codes used by the `novels.status` column. Mirrors the lnreader NovelStatus enum the
 * plugins return as strings, kept as ints to match the manga side's `mangas.status` column shape.
 * The seven values line up 1:1 with `SManga`'s status constants, so shared library code (the
 * completed filter, status grouping) reads a status off either content type without translating.
 */
object NovelStatusCode {
    const val UNKNOWN = 0
    const val ONGOING = 1
    const val COMPLETED = 2
    const val LICENSED = 3
    const val PUBLISHING_FINISHED = 4
    const val CANCELLED = 5
    const val ON_HIATUS = 6

    // The words an LNReader plugin states a status in, which is the form a source hands novels over in.
    private val names = mapOf(
        ONGOING to "Ongoing",
        COMPLETED to "Completed",
        LICENSED to "Licensed",
        PUBLISHING_FINISHED to "Publishing Finished",
        CANCELLED to "Cancelled",
        ON_HIATUS to "On Hiatus",
    )

    fun fromString(status: String?): Int =
        names.entries.firstOrNull { it.value == status?.trim() }?.key ?: UNKNOWN

    /** [code] in a source's words, for a source that states it as a number; null when unknown. */
    fun toSourceString(code: Int): String? = names[code]

    /** Display label resource for a `novels.status` code; unknown/unrecognized falls back to `unknown`. */
    fun toStringRes(status: Long): StringResource = when (status.toInt()) {
        ONGOING -> MR.strings.ongoing
        COMPLETED -> MR.strings.completed
        LICENSED -> MR.strings.licensed
        PUBLISHING_FINISHED -> MR.strings.publishing_finished
        CANCELLED -> MR.strings.cancelled
        ON_HIATUS -> MR.strings.on_hiatus
        else -> MR.strings.unknown
    }
}

/**
 * Translate a freshly-parsed [SourceNovel] (lnreader plugin output) into an unsaved domain [Novel]
 * (`id = -1L`). A refresh stores it over the stored row with [storeRefreshedNovel]; a novel opened
 * from Browse is inserted through [reikai.domain.novel.NovelRepository.insertOrGet]. `genres` is a
 * comma-joined string per the lnreader convention, split to a list here.
 */
fun SourceNovel.toNovel(
    sourceId: String,
    favorite: Boolean = true,
    now: Long = System.currentTimeMillis(),
): Novel = Novel(
    id = -1L,
    source = sourceId,
    url = path,
    title = name?.let { NovelTextSanitizer.decodeEntities(it) } ?: "Untitled",
    author = author?.let { NovelTextSanitizer.decodeEntities(it) },
    artist = artist?.let { NovelTextSanitizer.decodeEntities(it) },
    description = summary?.let { NovelTextSanitizer.decodeEntities(it) },
    genre = genres?.split(",")?.map { NovelTextSanitizer.decodeEntities(it).trim() }?.filter { it.isNotEmpty() },
    status = NovelStatusCode.fromString(status).toLong(),
    thumbnailUrl = cover,
    favorite = favorite,
    lastUpdate = 0L,
    initialized = true,
    chapterFlags = 0L,
    dateAdded = if (favorite) now else 0L,
    updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
    coverLastModified = 0L,
    totalPages = totalPages.toLong(),
    lastReadAt = null,
    notes = "",
    viewerFlags = 0L,
    version = 0L,
)

/**
 * Translate a [ChapterItem] (lnreader plugin's chapter list entry) into an unsaved domain
 * [NovelChapter] (`id = -1L`). [syncChaptersWithNovelSource] matches it by URL against the novel's
 * stored rows, inserting it only when none matches. `read`/`bookmark`/`lastTextProgress` start
 * cleared; the reader updates them as the user reads.
 */
fun ChapterItem.toNovelChapter(
    novelId: Long,
    sourceOrder: Long = 0L,
    now: Long = System.currentTimeMillis(),
): NovelChapter = NovelChapter(
    id = -1L,
    novelId = novelId,
    url = path,
    name = NovelTextSanitizer.decodeEntities(name),
    read = false,
    bookmark = false,
    lastTextProgress = 0L,
    chapterNumber = chapterNumber ?: 0.0,
    sourceOrder = sourceOrder,
    dateFetch = now,
    dateUpload = NovelDateParser.parse(releaseTime, now),
    page = page.orEmpty(),
)
