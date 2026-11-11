package reikai.data.backup

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupChapterTombstone
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelChapter
import eu.kanade.tachiyomi.data.backup.models.BackupSearchMetadata
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.jupiter.api.Test
import tachiyomi.data.Database

/**
 * Every table of the schema is either carried by a backup field that exists, or named below as left out with
 * its reason. A new table fails this until someone decides which, so a reinstall cannot silently lose it.
 * Whether each field restores is pinned by the round-trip tests beside this one, not here.
 */
class BackupTableCoverageTest {

    @Test
    fun `every table is backed up or deliberately left out`() = runTest {
        val unaccounted = schemaTables() - COVERED.keys - LEFT_OUT.keys - KNOWN_GAPS.keys

        unaccounted.shouldBeEmpty()
    }

    @Test
    fun `no listed table is missing from the schema`() = runTest {
        val stale = (COVERED.keys + LEFT_OUT.keys + KNOWN_GAPS.keys) - schemaTables()

        stale.shouldBeEmpty()
    }

    @Test
    fun `every field a table is said to ride on exists`() {
        val missing = COVERED.flatMap { (table, fields) ->
            fields.filterNot { it.exists() }.map { "$table -> ${it.descriptor.serialName}.${it.number}" }
        }

        missing shouldBe emptyList()
    }

    private suspend fun schemaTables(): Set<String> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            Database.Schema.create(driver).await()
            return driver.executeQuery(
                null,
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
                { cursor -> QueryResult.Value(buildSet { while (cursor.next().value) add(cursor.getString(0)!!) }) },
                0,
            ).await()
        } finally {
            driver.close()
        }
    }

    class Field(val descriptor: SerialDescriptor, val number: Int) {
        fun exists() = (0 until descriptor.elementsCount).any { index ->
            descriptor.getElementAnnotations(index).any { it is ProtoNumber && it.number == number }
        }
    }

    companion object {
        private val ROOT = Backup.serializer().descriptor
        private val MANGA = BackupManga.serializer().descriptor
        private val NOVEL = BackupNovel.serializer().descriptor
        private val CHAPTER = BackupChapter.serializer().descriptor
        private val NOVEL_CHAPTER = BackupNovelChapter.serializer().descriptor
        private val CATEGORY = BackupCategory.serializer().descriptor
        private val TOMBSTONE = BackupChapterTombstone.serializer().descriptor
        private val METADATA = BackupSearchMetadata.serializer().descriptor

        private fun on(descriptor: SerialDescriptor, vararg numbers: Int) = numbers.map { Field(descriptor, it) }

        /** Table to the backup fields that carry its rows. */
        private val COVERED: Map<String, List<Field>> = mapOf(
            "manga" to on(ROOT, 1),
            "chapter" to on(MANGA, 16),
            "history" to on(MANGA, 104),
            "manga_track" to on(MANGA, 18),
            "manga_category" to on(MANGA, 17),
            "category" to on(ROOT, 2, 701),
            "excluded_scanlator" to on(MANGA, 108),
            "custom_manga_info" to on(MANGA, 602, 603, 800, 801, 802, 804, 805),
            "search_metadata" to on(MANGA, 700),
            "search_tags" to on(METADATA, 5),
            "search_titles" to on(METADATA, 6),
            "manga_chapter_number_override" to on(CHAPTER, 701),
            "manga_chapter_copy_mark" to on(CHAPTER, 702),
            "manga_chapter_tombstone" to on(MANGA, 704),
            "manga_chapter_tombstone_session" to on(TOMBSTONE, 11),
            "reading_session" to on(MANGA, 701),
            "novels" to on(ROOT, 700),
            "novel_chapters" to on(NOVEL, 20),
            "novel_history" to on(NOVEL, 23),
            "novel_tracks" to on(NOVEL, 22),
            "novels_categories" to on(NOVEL, 21),
            "custom_novel_info" to on(NOVEL, 602, 603, 800, 801, 802, 804, 805),
            "novel_chapter_number_override" to on(NOVEL_CHAPTER, 12),
            "novel_chapter_copy_mark" to on(NOVEL_CHAPTER, 14),
            "novel_chapter_tombstone" to on(NOVEL, 704),
            "novel_chapter_tombstone_session" to on(TOMBSTONE, 11),
            "novel_reading_session" to on(NOVEL, 28),
            "novel_chapter_words" to on(NOVEL_CHAPTER, 13),
            "local_novel_language" to on(NOVEL, 29),
            "library_manual_order" to on(MANGA, 702) + on(NOVEL, 702),
            "library_manual_order_base" to on(CATEGORY, 8002),
            "local_volume" to on(MANGA, 703) + on(NOVEL, 703),
            "local_volume_owner" to on(MANGA, 705) + on(NOVEL, 705),
            "local_volume_pick" to on(MANGA, 706) + on(NOVEL, 706),
            // Membership only; a group's source order and override flag are left out (merged-series.md, Decisions).
            "merge_group" to on(ROOT, 702, 711),
            "merge_group_manga" to on(ROOT, 711),
            "merge_group_novel" to on(ROOT, 702),
            "saved_search" to on(ROOT, 715),
            "feed_saved_search" to on(ROOT, 716),
            "extension_store" to on(ROOT, 106),
            "source" to on(ROOT, 101),
        )

        /** Tables a reinstall rebuilds, or that hold nothing a user set up, with why. */
        private val LEFT_OUT: Map<String, String> = mapOf(
            "merged_chapter_unit" to "stitch cache, restitched by ReconcileMergedChapters.afterPass after a restore",
            "merged_novel_chapter_unit" to "stitch cache, as above",
            "merged_group_ranking" to "the ranking the last stitch ran under, rebuilt with it",
            "merged_member_count" to "counts the last stitch ranked on, rebuilt with it",
            "merged_group_pending" to "groups queued for a restitch, written by triggers",
            "library_update_errors" to "the last update's failures, rewritten by the next update",
            "novel_update_errors" to "the last update's failures, rewritten by the next update",
            "dedupe_merged_ids" to "an upgrade migration's record, emptied once its carry finishes",
            "dedupe_merged_chapter_ids" to "an upgrade migration's record, emptied once its carry finishes",
            "taste_library" to "a cache of the tracker libraries, pulled again from the trackers",
            // data-and-backup.md, Decisions: a row is a disk step on the first device's storage.
            "local_file_journal" to "an in-flight file operation on this device's storage, replayed at start here only",
        )

        /** User-set rows no backup carries yet and no doc rules out. Each is an open gap, not a decision. */
        private val KNOWN_GAPS: Map<String, String> = emptyMap()
    }
}
