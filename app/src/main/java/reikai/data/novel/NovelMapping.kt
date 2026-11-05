package reikai.data.novel

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import mihon.core.common.extensions.toInstantOrNull
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.host.ChapterItem
import reikai.novel.host.NovelItem
import reikai.novel.host.NovelTextSanitizer
import reikai.novel.host.SourceNovel
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Status codes used by the `novels.status` column. Mirrors the lnreader NovelStatus enum the
 * plugins return as strings, kept as ints to match the manga side's `manga.remote_status` column shape.
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

    // LNReader's Inactive and STUB have no code in manga's table, so each reads as the nearest one.
    private val codes = names.entries.associate { (code, word) -> word to code } +
        mapOf("Inactive" to ON_HIATUS, "STUB" to LICENSED)

    fun fromString(status: String?): Int = codes[status?.trim()] ?: UNKNOWN

    /** [code] in a source's words, for a source that states it as a number; null when unknown. */
    fun toSourceString(code: Int): String? = names[code]
}

// Stored for a novel its source names nothing.
private const val UNTITLED = "Untitled"

// Plugins fill a missing name with one of these (novelhall.ts `|| 'Untitled'`, webnovel.ts `'No Title Found'`).
private val PLACEHOLDER_NAMES = setOf(UNTITLED, "No Title Found")

/**
 * The name a source sent, or null for a blank one or a plugin's placeholder. Untrimmed, since a stored
 * title names the novel's download folder.
 */
fun sentNovelName(name: String?): String? = name?.takeUnless { it.isBlank() || it.trim() in PLACEHOLDER_NAMES }

/** The parse's name, or null when it names nothing. */
val SourceNovel.sentName: String?
    get() = sentNovelName(name)

/**
 * Translate a freshly-parsed [SourceNovel] (lnreader plugin output) into an unsaved domain [Novel]
 * (`id = -1L`). A refresh stores it over the stored row with [storeRefreshedNovel]; a novel opened
 * from Browse is inserted through [reikai.domain.novel.NovelRepository.insertOrGet]. `genres` is a
 * comma-joined string per the lnreader convention, split to a list here. The details arrive decoded:
 * each scraping adapter decodes its own ([reikai.novel.host.withDecodedDetails]).
 */
fun SourceNovel.toNovel(
    sourceId: String,
    favorite: Boolean = true,
    now: Instant = Clock.System.now(),
): Novel = Novel(
    id = -1L,
    source = sourceId,
    url = path,
    title = sentName ?: UNTITLED,
    author = author,
    artist = artist,
    description = summary,
    genre = genres?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() },
    status = NovelStatusCode.fromString(status).toLong(),
    thumbnailUrl = cover,
    favoriteAt = now.takeIf { favorite },
    lastUpdate = null,
    initialized = true,
    chapterFlags = 0L,
    updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
    coverLastModified = null,
    totalPages = totalPages.toLong(),
    notes = "",
    viewerFlags = 0L,
)

/**
 * A browsed or searched [NovelItem] as an unsaved, unfavorited [Novel] on [sourceId], carrying only
 * what a list row has; the details and chapters arrive with the first refresh.
 */
fun NovelItem.toNovel(sourceId: String): Novel =
    Novel.create().copy(source = sourceId, url = path, title = sentNovelName(name) ?: UNTITLED, thumbnailUrl = cover)

/**
 * Translate a [ChapterItem] (lnreader plugin's chapter list entry) into an unsaved domain
 * [NovelChapter] (`id = -1L`). [syncChaptersWithNovelSource] matches it by URL against the novel's
 * stored rows, inserting it only when none matches. `read`/`bookmark`/`lastTextProgress` start
 * cleared; the reader updates them as the user reads. The name and group arrive decoded, as the details
 * do ([reikai.novel.host.withDecodedText]).
 */
fun ChapterItem.toNovelChapter(
    novelId: Long,
    sourceOrder: Long = 0L,
    now: Instant = Clock.System.now(),
): NovelChapter = NovelChapter(
    id = -1L,
    novelId = novelId,
    url = path,
    name = NovelTextSanitizer.stripInvalidChars(name),
    read = false,
    bookmark = false,
    lastTextProgress = 0L,
    chapterNumber = chapterNumber ?: 0.0,
    sourceOrder = sourceOrder,
    dateFetch = now,
    // A date the plugin gives none for, or one it cannot be read from, is unknown, as manga's source API 0 is
    dateUpload = NovelDateParser.parse(releaseTime, now.toEpochMilliseconds()).toInstantOrNull(),
    page = page.orEmpty(),
    // Manga's rule (copyFromSChapter): trimmed, and blank means none.
    scanlator = scanlator?.let(NovelTextSanitizer::stripInvalidChars)?.trim()?.ifEmpty { null },
)
