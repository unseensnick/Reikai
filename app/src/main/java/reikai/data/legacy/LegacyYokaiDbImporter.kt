package reikai.data.legacy

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelCategory
import eu.kanade.tachiyomi.data.backup.models.BackupNovelChapter
import eu.kanade.tachiyomi.data.backup.models.BackupNovelTracking
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.protobuf.ProtoBuf
import okio.buffer
import okio.gzip
import okio.sink
import reikai.domain.library.ReikaiLibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Recovers a library left behind when a user updates in place from the old Yōkai-based build.
 *
 * Both forks ship the same `tachiyomi.db` filename so installs upgrade in place, but the Yōkai
 * schema sits at a higher (and otherwise unrelated) version than Mihon's. SQLDelight therefore
 * treats the on-disk DB as "newer than the code", runs no migrations, and crashes on the first
 * query against a table Yōkai never had (e.g. `extension_store`). To recover instead of crash, we
 * read the old library out with plain SQL, write it as a normal backup, and move the old DB aside
 * so a fresh Mihon DB is created. The restore is enqueued by [eu.kanade.tachiyomi.App] once DI is
 * ready. Settings and tracker logins are untouched: those live in SharedPreferences, which survive
 * the in-place update.
 */
object LegacyYokaiDbImporter {

    private const val DB_NAME = "tachiyomi.db"
    private const val IMPORT_BACKUP_NAME = "legacy_yokai_import.tachibk"
    private const val ASIDE_SUFFIX = ".yokai.bak"

    // android.util.Log, not the logcat extension: this runs before LogcatLogger is installed in
    // App.onCreate, so the extension would silently drop these lines.
    private const val TAG = "LegacyYokaiImport"

    /**
     * If the on-disk database is a legacy Yōkai one, export its library to a backup file and move
     * the old DB aside so the app can start with a fresh DB. Returns the backup file to restore, or
     * null when there is nothing to do (no DB, or already a Mihon DB).
     */
    fun prepareIfLegacyDb(context: Context): File? {
        val dbFile = context.getDatabasePath(DB_NAME)
        if (!dbFile.exists()) return null

        val db = runCatching {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        }.getOrNull() ?: return null

        if (!db.isLegacyYokaiSchema()) {
            db.close()
            return null
        }

        Log.i(TAG, "Legacy Yokai database detected; recovering library before reset")

        val libraryPreferences = Injekt.get<ReikaiLibraryPreferences>()
        var backupFile: File? = null
        try {
            val backup = db.buildBackup()
            backupFile = writeBackup(context, backup)
            Log.i(
                TAG,
                "Recovered ${backup.backupManga.size} manga, ${backup.backupNovels.size} novels, " +
                    "${backup.backupExtensionStores.size} repos",
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to recover legacy Yokai library; resetting DB anyway", e)
        } finally {
            db.close()
            resetMergeState(libraryPreferences)
            // Always move the old DB aside once detected, so the app starts even if extraction
            // failed. Renamed, never deleted, so the data stays recoverable.
            moveAside(dbFile)
            moveAside(File(dbFile.path + "-wal"))
            moveAside(File(dbFile.path + "-shm"))
        }
        return backupFile
    }

    /**
     * Land the migrated library unmerged. A merged group renders under a single representative member, so
     * after a reset (which reassigns ids, and so which member represents a group) cross-category members
     * leave categories looking empty. Clearing the stale merge prefs and disabling same-title auto-merge
     * makes every entry show in all its categories; the user can re-merge in-app. Migration-only: this runs
     * solely from the legacy-DB path, so a normal restore on a fresh install is unaffected.
     */
    private fun resetMergeState(prefs: ReikaiLibraryPreferences) {
        prefs.mangaManualMerges.set(emptySet())
        prefs.mangaManualUnmerges.set(emptySet())
        prefs.novelManualMerges.set(emptySet())
        prefs.novelManualUnmerges.set(emptySet())
        prefs.autoMergeSameTitle.set(false)
        prefs.novelAutoMergeSameTitle.set(false)
    }

    /** Yōkai's `categories` table carries a `manga_order` column that Mihon's never has. */
    private fun SQLiteDatabase.isLegacyYokaiSchema(): Boolean =
        runCatching { hasColumn("categories", "manga_order") }.getOrDefault(false)

    private fun SQLiteDatabase.buildBackup(): Backup {
        // Manga categories: order value is keyed on for restore, so map id -> sort.
        val categories = mutableListOf<BackupCategory>()
        val categorySortById = HashMap<Long, Long>()
        rawQuery("SELECT _id, name, sort, flags FROM categories WHERE _id > 0", null).use { c ->
            while (c.moveToNext()) {
                val sort = c.longOr("sort")
                categorySortById[c.longOr("_id")] = sort
                categories += BackupCategory(name = c.strOr("name"), order = sort, flags = c.longOr("flags"))
            }
        }
        val mangaCategoryOrders = groupCategoryOrders("mangas_categories", "manga_id", categorySortById)

        val chaptersByManga = HashMap<Long, MutableList<BackupChapter>>()
        val chapterUrlById = HashMap<Long, String>()
        val chapterMangaById = HashMap<Long, Long>()
        rawQuery(
            "SELECT _id, manga_id, url, name, scanlator, read, bookmark, last_page_read, " +
                "chapter_number, source_order, date_fetch, date_upload FROM chapters",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.longOr("_id")
                val mangaId = c.longOr("manga_id")
                val url = c.strOr("url")
                chapterUrlById[id] = url
                chapterMangaById[id] = mangaId
                chaptersByManga.getOrPut(mangaId) { mutableListOf() } += BackupChapter(
                    url = url,
                    name = c.strOr("name"),
                    scanlator = c.strOrNull("scanlator"),
                    read = c.boolOr("read"),
                    bookmark = c.boolOr("bookmark"),
                    lastPageRead = c.longOr("last_page_read"),
                    chapterNumber = c.floatOr("chapter_number"),
                    sourceOrder = c.longOr("source_order"),
                    dateFetch = c.longOr("date_fetch"),
                    dateUpload = c.longOr("date_upload"),
                )
            }
        }

        val historyByManga = HashMap<Long, MutableList<BackupHistory>>()
        rawQuery("SELECT history_chapter_id, history_last_read, history_time_read FROM history", null).use { c ->
            while (c.moveToNext()) {
                val chapterId = c.longOr("history_chapter_id")
                val url = chapterUrlById[chapterId] ?: continue
                val mangaId = chapterMangaById[chapterId] ?: continue
                historyByManga.getOrPut(mangaId) { mutableListOf() } += BackupHistory(
                    url = url,
                    lastRead = c.longOr("history_last_read"),
                    readDuration = c.longOr("history_time_read"),
                )
            }
        }

        val tracksByManga = HashMap<Long, MutableList<BackupTracking>>()
        rawQuery(
            "SELECT manga_id, sync_id, remote_id, library_id, title, last_chapter_read, " +
                "total_chapters, status, score, remote_url, start_date, finish_date FROM manga_sync",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                tracksByManga.getOrPut(c.longOr("manga_id")) { mutableListOf() } += BackupTracking(
                    syncId = c.intOr("sync_id"),
                    libraryId = c.longOr("library_id"),
                    mediaId = c.longOr("remote_id"),
                    trackingUrl = c.strOr("remote_url"),
                    title = c.strOr("title"),
                    lastChapterRead = c.floatOr("last_chapter_read"),
                    totalChapters = c.intOr("total_chapters"),
                    score = c.floatOr("score"),
                    status = c.intOr("status"),
                    startedReadingDate = c.longOr("start_date"),
                    finishedReadingDate = c.longOr("finish_date"),
                )
            }
        }

        val backupManga = mutableListOf<BackupManga>()
        rawQuery(
            "SELECT _id, source, url, title, artist, author, description, genre, status, " +
                "thumbnail_url, viewer, chapter_flags, date_added, update_strategy, initialized " +
                "FROM mangas WHERE favorite = 1",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.longOr("_id")
                val source = c.longOr("source")
                val url = c.strOr("url")
                backupManga += BackupManga(
                    source = source,
                    url = url,
                    title = c.strOr("title"),
                    artist = c.strOrNull("artist"),
                    author = c.strOrNull("author"),
                    description = c.strOrNull("description"),
                    genre = splitGenre(c.strOrNull("genre")),
                    status = c.intOr("status"),
                    thumbnailUrl = c.strOrNull("thumbnail_url"),
                    viewer = c.intOr("viewer"),
                    chapterFlags = c.intOr("chapter_flags"),
                    dateAdded = c.longOr("date_added"),
                    updateStrategy = updateStrategyOf(c.longOr("update_strategy")),
                    initialized = c.boolOr("initialized", default = true),
                    favorite = true,
                    chapters = chaptersByManga[id].orEmpty(),
                    categories = mangaCategoryOrders[id].orEmpty(),
                    tracking = tracksByManga[id].orEmpty(),
                    history = historyByManga[id].orEmpty(),
                )
            }
        }

        return Backup(
            backupManga = backupManga,
            backupCategories = categories,
            backupExtensionStores = buildExtensionStores(),
            backupNovels = buildNovels(),
            backupNovelCategories = buildNovelCategories(),
        )
    }

    /**
     * Read the old `extension_repos` table (skipped by Mihon's migration 11, so still present) and convert
     * it to the current repo schema, mirroring that migration: index_url = base_url + "/repo.json", etc.
     */
    private fun SQLiteDatabase.buildExtensionStores(): List<BackupExtensionStore> {
        if (!hasTable("extension_repos")) return emptyList()
        val stores = mutableListOf<BackupExtensionStore>()
        rawQuery(
            "SELECT base_url, name, short_name, website, signing_key_fingerprint FROM extension_repos",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val baseUrl = c.strOr("base_url")
                if (baseUrl.isBlank()) continue
                val name = c.strOr("name")
                stores += BackupExtensionStore(
                    indexUrl = baseUrl.trimEnd('/') + "/repo.json",
                    name = name,
                    badgeLabel = c.strOrNull("short_name") ?: name,
                    signingKey = c.strOr("signing_key_fingerprint"),
                    contactWebsite = c.strOr("website"),
                    contactDiscord = null,
                    isLegacy = true,
                    extensionListUrl = null,
                )
            }
        }
        return stores
    }

    private fun SQLiteDatabase.buildNovelCategories(): List<BackupNovelCategory> {
        if (!hasTable("novel_categories")) return emptyList()
        val categories = mutableListOf<BackupNovelCategory>()
        rawQuery("SELECT _id, name, sort, flags FROM novel_categories WHERE _id > 0", null).use { c ->
            while (c.moveToNext()) {
                categories += BackupNovelCategory(
                    name = c.strOr("name"),
                    order = c.longOr("sort"),
                    flags = c.longOr("flags"),
                )
            }
        }
        return categories
    }

    private fun SQLiteDatabase.buildNovels(): List<BackupNovel> {
        if (!hasTable("novels")) return emptyList()

        val categorySortById = HashMap<Long, Long>()
        rawQuery("SELECT _id, sort FROM novel_categories WHERE _id > 0", null).use { c ->
            while (c.moveToNext()) categorySortById[c.longOr("_id")] = c.longOr("sort")
        }
        val novelCategoryOrders = groupCategoryOrders("novels_categories", "novel_id", categorySortById)

        val chaptersByNovel = HashMap<Long, MutableList<BackupNovelChapter>>()
        rawQuery(
            "SELECT novel_id, url, name, read, bookmark, last_text_progress, chapter_number, " +
                "source_order, date_fetch, date_upload, page FROM novel_chapters",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                chaptersByNovel.getOrPut(c.longOr("novel_id")) { mutableListOf() } += BackupNovelChapter(
                    url = c.strOr("url"),
                    name = c.strOr("name"),
                    read = c.boolOr("read"),
                    bookmark = c.boolOr("bookmark"),
                    lastTextProgress = c.longOr("last_text_progress"),
                    chapterNumber = c.doubleOr("chapter_number"),
                    sourceOrder = c.longOr("source_order"),
                    dateFetch = c.longOr("date_fetch"),
                    dateUpload = c.longOr("date_upload"),
                    page = c.strOr("page"),
                )
            }
        }

        val tracksByNovel = HashMap<Long, MutableList<BackupNovelTracking>>()
        if (hasTable("novel_tracks")) {
            rawQuery(
                "SELECT novel_id, sync_id, remote_id, library_id, title, last_chapter_read, " +
                    "total_chapters, status, score, remote_url, start_date, finish_date FROM novel_tracks",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    tracksByNovel.getOrPut(c.longOr("novel_id")) { mutableListOf() } += BackupNovelTracking(
                        trackerId = c.longOr("sync_id"),
                        remoteId = c.longOr("remote_id"),
                        libraryId = c.longOrNull("library_id"),
                        title = c.strOr("title"),
                        lastChapterRead = c.doubleOr("last_chapter_read"),
                        totalChapters = c.longOr("total_chapters"),
                        status = c.longOr("status"),
                        score = c.doubleOr("score"),
                        remoteUrl = c.strOr("remote_url"),
                        startDate = c.longOr("start_date"),
                        finishDate = c.longOr("finish_date"),
                    )
                }
            }
        }

        val novels = mutableListOf<BackupNovel>()
        rawQuery(
            "SELECT _id, source, url, title, author, artist, description, genre, status, " +
                "thumbnail_url, last_update, initialized, chapter_flags, date_added, update_strategy, " +
                "cover_last_modified, total_pages, last_read_at FROM novels WHERE favorite = 1",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.longOr("_id")
                novels += BackupNovel(
                    source = c.strOr("source"),
                    url = c.strOr("url"),
                    title = c.strOr("title"),
                    artist = c.strOrNull("artist"),
                    author = c.strOrNull("author"),
                    description = c.strOrNull("description"),
                    genre = splitGenre(c.strOrNull("genre")),
                    status = c.longOr("status"),
                    thumbnailUrl = c.strOrNull("thumbnail_url"),
                    dateAdded = c.longOr("date_added"),
                    lastUpdate = c.longOr("last_update"),
                    initialized = c.boolOr("initialized", default = true),
                    chapterFlags = c.longOr("chapter_flags"),
                    updateStrategy = updateStrategyOf(c.longOr("update_strategy")),
                    coverLastModified = c.longOr("cover_last_modified"),
                    totalPages = c.longOr("total_pages", default = 1),
                    lastReadAt = c.longOrNull("last_read_at"),
                    favorite = true,
                    chapters = chaptersByNovel[id].orEmpty(),
                    categories = novelCategoryOrders[id].orEmpty(),
                    tracking = tracksByNovel[id].orEmpty(),
                )
            }
        }
        return novels
    }

    private fun SQLiteDatabase.groupCategoryOrders(
        joinTable: String,
        idColumn: String,
        categorySortById: Map<Long, Long>,
    ): Map<Long, MutableList<Long>> {
        val result = HashMap<Long, MutableList<Long>>()
        if (!hasTable(joinTable)) return result
        rawQuery("SELECT $idColumn, category_id FROM $joinTable", null).use { c ->
            while (c.moveToNext()) {
                val order = categorySortById[c.longOr("category_id")] ?: continue
                result.getOrPut(c.longOr(idColumn)) { mutableListOf() } += order
            }
        }
        return result
    }

    private fun writeBackup(context: Context, backup: Backup): File {
        val parser = Injekt.get<ProtoBuf>()
        val bytes = parser.encodeToByteArray(Backup.serializer(), backup)
        val file = File(context.cacheDir, IMPORT_BACKUP_NAME)
        file.sink().gzip().buffer().use { it.write(bytes) }
        return file
    }

    private fun moveAside(file: File) {
        if (!file.exists()) return
        val aside = File(file.parentFile, file.name + ASIDE_SUFFIX)
        aside.delete()
        file.renameTo(aside)
    }

    private fun splitGenre(raw: String?): List<String> =
        raw?.split(",")?.mapNotNull { it.trim().ifBlank { null } }.orEmpty()

    private fun updateStrategyOf(value: Long): UpdateStrategy =
        UpdateStrategy.entries.getOrElse(value.toInt()) { UpdateStrategy.ALWAYS_UPDATE }
}

private fun SQLiteDatabase.hasTable(table: String): Boolean =
    rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table))
        .use { it.moveToFirst() }

private fun SQLiteDatabase.hasColumn(table: String, column: String): Boolean =
    rawQuery("PRAGMA table_info($table)", null).use { c ->
        val nameIndex = c.getColumnIndex("name")
        if (nameIndex < 0) return false
        while (c.moveToNext()) if (c.getString(nameIndex) == column) return true
        false
    }

private fun Cursor.strOr(name: String, default: String = ""): String {
    val i = getColumnIndex(name)
    return if (i >= 0 && !isNull(i)) getString(i) else default
}

private fun Cursor.strOrNull(name: String): String? {
    val i = getColumnIndex(name)
    return if (i >= 0 && !isNull(i)) getString(i) else null
}

private fun Cursor.longOr(name: String, default: Long = 0): Long {
    val i = getColumnIndex(name)
    return if (i >= 0 && !isNull(i)) getLong(i) else default
}

private fun Cursor.longOrNull(name: String): Long? {
    val i = getColumnIndex(name)
    return if (i >= 0 && !isNull(i)) getLong(i) else null
}

private fun Cursor.intOr(name: String, default: Int = 0): Int {
    val i = getColumnIndex(name)
    return if (i >= 0 && !isNull(i)) getInt(i) else default
}

private fun Cursor.floatOr(name: String, default: Float = 0f): Float {
    val i = getColumnIndex(name)
    return if (i >= 0 && !isNull(i)) getFloat(i) else default
}

private fun Cursor.doubleOr(name: String, default: Double = 0.0): Double {
    val i = getColumnIndex(name)
    return if (i >= 0 && !isNull(i)) getDouble(i) else default
}

private fun Cursor.boolOr(name: String, default: Boolean = false): Boolean {
    val i = getColumnIndex(name)
    return if (i >= 0 && !isNull(i)) getLong(i) != 0L else default
}
