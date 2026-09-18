package reikai.data.legacy

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import reikai.domain.library.CATEGORY_SORT_CUSTOMIZED
import tachiyomi.domain.library.model.LibrarySort
import java.io.File

/**
 * Reads the sort out of a Yokai-shaped database the way the importer does, so a query that drops the
 * `manga_order` / `novel_order` column fails here rather than only in the translation kernel's test.
 * The `flags` column carries a value Yokai never read, to prove it no longer leaks through.
 */
@RunWith(AndroidJUnit4::class)
class LegacyYokaiDbImporterTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var file: File
    private lateinit var db: SQLiteDatabase

    @Before
    fun setUp() {
        file = File(context.cacheDir, "legacy_yokai_probe.db").also { it.delete() }
        db = SQLiteDatabase.openOrCreateDatabase(file, null)
        YOKAI_TABLES.forEach(db::execSQL)
        db.execSQL("INSERT INTO categories VALUES (1, 'Reading', 0, 32, 'g')")
        db.execSQL("INSERT INTO novel_categories VALUES (1, 'Reading', 0, 32, 'g')")
    }

    @After
    fun tearDown() {
        db.close()
        file.delete()
    }

    @Test
    fun aMangaCategoryKeepsItsYokaiSort() {
        val flags = with(LegacyYokaiDbImporter) { db.buildBackup(composeMangaLibrary = false) }
            .backupCategories.single().flags

        assertEquals(LAST_READ_NEWEST_FIRST, flags)
    }

    @Test
    fun aNovelCategoryKeepsItsYokaiSort() {
        val flags = with(LegacyYokaiDbImporter) { db.buildBackup(composeMangaLibrary = false) }
            .backupNovelCategories.single().flags

        assertEquals(LAST_READ_OLDEST_FIRST, flags)
    }

    private companion object {
        val LAST_READ_NEWEST_FIRST = LibrarySort(LibrarySort.Type.LastRead, LibrarySort.Direction.Descending)
            .flag or CATEGORY_SORT_CUSTOMIZED
        val LAST_READ_OLDEST_FIRST = LibrarySort(LibrarySort.Type.LastRead, LibrarySort.Direction.Ascending)
            .flag or CATEGORY_SORT_CUSTOMIZED

        // Only the columns the importer selects; the rest of Yokai's schema is irrelevant here.
        val YOKAI_TABLES = listOf(
            "CREATE TABLE categories(_id INTEGER PRIMARY KEY, name TEXT, sort INTEGER, flags INTEGER, " +
                "manga_order TEXT)",
            "CREATE TABLE novel_categories(_id INTEGER PRIMARY KEY, name TEXT, sort INTEGER, flags INTEGER, " +
                "novel_order TEXT)",
            "CREATE TABLE chapters(_id INTEGER, manga_id INTEGER, url TEXT, name TEXT, scanlator TEXT, " +
                "read INTEGER, bookmark INTEGER, last_page_read INTEGER, chapter_number REAL, " +
                "source_order INTEGER, date_fetch INTEGER, date_upload INTEGER)",
            "CREATE TABLE history(history_chapter_id INTEGER, history_last_read INTEGER, " +
                "history_time_read INTEGER)",
            "CREATE TABLE manga_sync(manga_id INTEGER, sync_id INTEGER, remote_id INTEGER, " +
                "library_id INTEGER, title TEXT, last_chapter_read REAL, total_chapters INTEGER, " +
                "status INTEGER, score REAL, remote_url TEXT, start_date INTEGER, finish_date INTEGER)",
            "CREATE TABLE mangas(_id INTEGER, source INTEGER, url TEXT, title TEXT, artist TEXT, " +
                "author TEXT, description TEXT, genre TEXT, status INTEGER, thumbnail_url TEXT, " +
                "viewer INTEGER, chapter_flags INTEGER, date_added INTEGER, update_strategy INTEGER, " +
                "initialized INTEGER, favorite INTEGER)",
        )
    }
}
