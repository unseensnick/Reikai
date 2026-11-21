package reikai.data.backup.lnreader

import eu.kanade.tachiyomi.data.backup.BackupProtoReader
import eu.kanade.tachiyomi.data.backup.create.creators.toBackupNovel
import eu.kanade.tachiyomi.data.backup.create.creators.toBackupNovelChapter
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelCategory
import eu.kanade.tachiyomi.data.backup.models.BackupNovelHistory
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import logcat.LogPriority
import okio.buffer
import okio.source
import reikai.data.novel.toNovel
import reikai.data.novel.toSourceChapters
import reikai.domain.reader.ChapterProgress
import reikai.novel.host.ChapterItem
import reikai.novel.host.SourceNovel
import reikai.novel.host.withDecodedDetails
import reikai.novel.host.withDecodedText
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.time.Instant

/** One file of an LNReader backup's NovelAndChapters folder: a row of LNReader's novel table, chapters inline. */
@Serializable
internal data class LnReaderNovel(
    val id: Long,
    val path: String,
    val pluginId: String,
    val name: String? = null,
    val cover: String? = null,
    val summary: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val status: String? = null,
    val genres: String? = null,
    @Serializable(with = LenientBoolean::class) val inLibrary: Boolean = false,
    @Serializable(with = LenientBoolean::class) val isLocal: Boolean = false,
    val totalPages: Int = 1,
    val lastUpdatedAt: String? = null,
    val lastReadAt: String? = null,
    val chapters: List<LnReaderChapter> = emptyList(),
)

@Serializable
internal data class LnReaderChapter(
    val path: String,
    val name: String = "",
    val releaseTime: String? = null,
    val chapterNumber: Double? = null,
    val page: String? = null,
    val position: Int = 0,
    val scanlator: String? = null,
    @Serializable(with = LenientBoolean::class) val bookmark: Boolean = false,
    @Serializable(with = LenientBoolean::class) val unread: Boolean = true,
    val readTime: String? = null,
    /** A whole percent, 0 to 100. */
    val progress: Int? = null,
    /** Milliseconds. */
    val timeSpent: Long = 0,
)

@Serializable
internal data class LnReaderCategory(val id: Long, val name: String, val novelIds: List<Long> = emptyList())

@Serializable
internal data class LnReaderPlugin(
    val id: String,
    val name: String = "",
    val url: String,
    val site: String? = null,
    val lang: String? = null,
    val iconUrl: String? = null,
)

/** An LNReader backup written in a format newer than this version reads, which would otherwise be misread. */
internal class UnsupportedLnReaderFormat(val formatVersion: Int) :
    IOException("LNReader backup format $formatVersion is newer than the supported $SUPPORTED_FORMAT_VERSION")

/** What the import needs before its novels: their categories and the plugins they came from. */
internal data class LnReaderHeader(val categories: List<LnReaderCategory>, val plugins: List<LnReaderPlugin>)

// The file format 1 writes, the 2.0.x and 2.1.x apps, stores booleans as 0 and 1 where format 2 writes JSON
// booleans, and nulls where a column was never set, so both are read and a null takes the default.
private val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

private object LenientBoolean : JsonTransformingSerializer<Boolean>(Boolean.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        (element as? JsonPrimitive)?.let { it.booleanOrNull ?: it.intOrNull?.let { n -> n != 0 } }
            ?.let(::JsonPrimitive) ?: element
}

private const val NOVEL_FOLDER = "NovelAndChapters/"
private const val VERSION_FILE = "Version.json"
private const val CATEGORY_FILE = "Category.json"

// LNReader's SUPPORTED_BACKUP_FORMAT_VERSION as of the formats read here. Format 1 records no formatVersion, only an app
// version, so a backup without one reads as format 1.
private const val SUPPORTED_FORMAT_VERSION = 2

// Format 2 lists the installed plugins in a file of their own; format 1 keeps them in the settings dump.
private const val PLUGIN_FILE = "Plugins.json"
private const val SETTINGS_FILE = "Setting.json"
private const val SETTINGS_PLUGIN_KEY = "INSTALL_PLUGINS"

// LNReader's own two categories (database/constants.ts BUILT_IN_CATEGORY_IDS): Default is no category here,
// and Local holds only local novels, which are not imported.
private val BUILT_IN_CATEGORY_IDS = setOf(1L, 2L)

/**
 * An LNReader backup, a flat zip read twice through [open]: the header first, since Category.json may
 * follow the novels, then the novels one at a time, so a large library is never held whole.
 */
internal class LnReaderBackupReader(private val open: () -> InputStream) {

    /** Whether the file is an LNReader backup at all; anything else, a Reikai backup included, is not. */
    fun isLnReaderBackup(): Boolean {
        var hasVersion = false
        var hasNovels = false
        // A file that is no zip, a Reikai backup among them, reads as no entries or fails to read.
        runCatching {
            entries { name, _ ->
                hasVersion = hasVersion || name == VERSION_FILE
                hasNovels = hasNovels || name.startsWith(NOVEL_FOLDER)
            }
        }
        return hasVersion && hasNovels
    }

    fun header(): LnReaderHeader {
        var categories = emptyList<LnReaderCategory>()
        var plugins = emptyList<LnReaderPlugin>()
        entries { name, stream ->
            when (name) {
                VERSION_FILE -> formatVersion(read(stream))
                    ?.takeIf { it > SUPPORTED_FORMAT_VERSION }
                    ?.let { throw UnsupportedLnReaderFormat(it) }
                CATEGORY_FILE -> categories = json.decodeFromString(read(stream))
                PLUGIN_FILE -> plugins = pluginList(json.parseToJsonElement(read(stream)))
                SETTINGS_FILE -> if (plugins.isEmpty()) {
                    (json.parseToJsonElement(read(stream)) as? JsonObject)?.get(SETTINGS_PLUGIN_KEY)
                        ?.let { plugins = pluginList(it) }
                }
            }
        }
        return LnReaderHeader(categories, plugins)
    }

    fun forEachNovel(block: (LnReaderNovel) -> Unit) {
        entries { name, stream ->
            if (name.startsWith(NOVEL_FOLDER) && name.endsWith(".json")) {
                // One file cut short or missing a field costs that novel, not the rest of the import.
                val novel = try {
                    json.decodeFromString<LnReaderNovel>(read(stream))
                } catch (e: SerializationException) {
                    logcat(LogPriority.WARN, e) { "LNReader import skipped $name" }
                    return@entries
                }
                block(novel)
            }
        }
    }

    /**
     * The library novels to import, in turn. A local novel's files stay in LNReader, so it is only named
     * ([onLocal]); one outside the library is left behind.
     */
    fun forEachImported(onLocal: (String) -> Unit, onNovel: (LnReaderNovel) -> Unit) {
        forEachNovel { novel ->
            when {
                !novel.inLibrary -> Unit
                novel.isLocal -> onLocal(novel.name ?: novel.path)
                else -> onNovel(novel)
            }
        }
    }

    private fun entries(block: (name: String, stream: ZipInputStream) -> Unit) {
        ZipInputStream(open()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { if (!it.isDirectory) block(it.name, zip) }
        }
    }

    // Bounded as a backup's own fields are: a crafted zip can inflate one entry past the heap.
    private fun read(stream: ZipInputStream): String {
        val entry = stream.source().buffer()
        if (entry.request(BackupProtoReader.MAX_FIELD_BYTES + 1)) throw IOException("A backup entry is too large")
        return entry.readUtf8()
    }

    // Unreadable is format 1's, as in LNReader, which refuses only a number it does not know.
    private fun formatVersion(text: String): Int? =
        (runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject)
            ?.get("formatVersion")?.let { (it as? JsonPrimitive)?.intOrNull }

    // The settings dump stores the list as a JSON string; Plugins.json stores the array itself.
    private fun pluginList(element: JsonElement): List<LnReaderPlugin> {
        val array = (element as? JsonPrimitive)?.takeIf { it.isString }?.let { json.parseToJsonElement(it.content) }
            ?: element
        return (array as? JsonArray)?.let {
            json.decodeFromJsonElement(ListSerializer(LnReaderPlugin.serializer()), it)
        }
            .orEmpty()
    }
}

// The user's own categories, in LNReader's order, which is each one's order in the backup.
private fun userCategories(categories: List<LnReaderCategory>) = categories.filter { it.id !in BUILT_IN_CATEGORY_IDS }

/** The categories a Reikai backup carries for [categories], each ordered by its place in LNReader's list. */
internal fun importedCategories(categories: List<LnReaderCategory>): List<BackupNovelCategory> =
    userCategories(categories).mapIndexed { i, it -> BackupNovelCategory(name = it.name, order = i.toLong()) }

/**
 * [this] as a Reikai backup entry, filed under the [importedCategories] orders that list it.
 * Its chapters go through the list mapper a sync uses, in LNReader's reading order (page, then
 * position; the backup writes them by row id). Imported chapters count as never fetched, or the Updates
 * tab, which lists what arrived after a novel was added, would list every one of them.
 */
internal fun LnReaderNovel.toImportedNovel(categories: List<LnReaderCategory>, zone: TimeZone): BackupNovel {
    val source = SourceNovel(
        path = path,
        name = name,
        // A cover LNReader stored itself is a path inside its own storage, which nothing here can open.
        cover = cover?.takeIf { it.startsWith("http") },
        genres = genres,
        summary = summary,
        author = author,
        artist = artist,
        status = status,
        totalPages = totalPages.coerceAtLeast(1),
    ).withDecodedDetails()
    val title = source.name.orEmpty()
    val ordered = chapters.sortedWith(compareBy({ it.page?.toIntOrNull() ?: 0 }, { it.position }))
    val byPath = ordered.associateBy { it.path }
    // Escaped as the plugin scraped it, which Reikai decodes as a plugin's text arrives.
    val items = ordered.map {
        ChapterItem(it.name, it.path, it.releaseTime, it.chapterNumber, it.page, it.scanlator).withDecodedText()
    }
    val importedChapters = items.toSourceChapters(novelId = -1L, novelTitle = title).map { chapter ->
        val from = byPath.getValue(chapter.url)
        chapter.copy(
            read = !from.unread,
            bookmark = from.bookmark,
            lastTextProgress =
            from.progress?.let { ChapterProgress.Percent.ofWhole(it.coerceIn(0, 100)).hundredths } ?: 0L,
            dateFetch = Instant.fromEpochMilliseconds(0),
        ).toBackupNovelChapter(sourceNumber = null, words = null, copyMark = null)
    }
    val categoryOrders = userCategories(categories).withIndex()
        .filter { (_, category) -> id in category.novelIds }
        .map { (order, _) -> order.toLong() }
    return source.toNovel(pluginId).copy(favoriteAt = Manga.UNKNOWN_FAVORITE_AT).toBackupNovel().apply {
        lastUpdate = lastUpdatedAt.toEpochMillis(zone) ?: 0L
        lastReadAt = this@toImportedNovel.lastReadAt.toEpochMillis(zone)
        this.chapters = importedChapters
        this.categories = categoryOrders
        history = ordered.mapNotNull { chapter ->
            chapter.readTime.toEpochMillis(zone)?.let { BackupNovelHistory(chapter.path, it, chapter.timeSpent) }
        }
    }
}

// LNReader writes read times with SQLite's datetime('now','localtime'), "YYYY-MM-DD HH:MM:SS" with no zone,
// and since 2.1.2 a novel's update time as ISO UTC ("2026-07-28T09:00:00.000Z").
private fun String?.toEpochMillis(zone: TimeZone): Long? = this?.trim()?.takeIf { it.isNotEmpty() }
    ?.let { text ->
        runCatching { Instant.parse(text) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(text.replace(' ', 'T')).toInstant(zone) }.getOrNull()
    }
    ?.toEpochMilliseconds()
