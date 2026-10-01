package mihon.core.migration.migrations

import android.content.Context
import eu.kanade.tachiyomi.data.cache.CoverCache
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.core.migration.MigrationContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.dedupe.MergedDuplicateDownloads
import reikai.domain.dedupe.MergedDuplicate
import reikai.domain.dedupe.MergedDuplicateChapter
import reikai.domain.dedupe.MergedDuplicateRepository
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import java.io.File

class MergedDuplicateCarryMigrationTest {

    @TempDir
    lateinit var dir: File

    // The real cache over a temp directory, so the files are named exactly as the app names them
    private val coverCache by lazy {
        CoverCache(
            mockk<Context> {
                every { getExternalFilesDir(any()) } answers { File(dir, firstArg<String>()).apply { mkdirs() } }
            },
        )
    }

    private val downloads = mockk<MergedDuplicateDownloads>(relaxed = true)

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a merged-away copy's custom cover moves to a survivor without one`(type: Type) = runTest {
        cover(type.entry(DISCARDED)).writeText("discarded")

        run(FakeRecord(MergedDuplicate(type.contentType, DISCARDED, SURVIVOR, TITLE)))

        cover(type.entry(SURVIVOR)).readText() shouldBe "discarded"
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `the survivor's own custom cover is kept over a merged-away copy's`(type: Type) = runTest {
        cover(type.entry(DISCARDED)).writeText("discarded")
        cover(type.entry(SURVIVOR)).writeText("survivor")

        run(FakeRecord(MergedDuplicate(type.contentType, DISCARDED, SURVIVOR, TITLE)))

        cover(type.entry(SURVIVOR)).readText() shouldBe "survivor"
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `no cover file is left under a merged-away id`(type: Type) = runTest {
        cover(type.entry(DISCARDED)).writeText("discarded")
        cover(type.entry(SURVIVOR)).writeText("survivor")

        run(FakeRecord(MergedDuplicate(type.contentType, DISCARDED, SURVIVOR, TITLE)))

        cover(type.entry(DISCARDED)).exists() shouldBe false
    }

    @Test
    fun `a merged-away novel's cover still under its pre-186 name moves to the survivor`() = runTest {
        // MigrateNovelCustomCoverKeysMigration re-keys only the novels still in the table after the merge
        coverCache.getCustomCoverFile(-DISCARDED).writeText("discarded")

        run(FakeRecord(MergedDuplicate(ContentType.NOVELS, DISCARDED, SURVIVOR, TITLE)))

        cover(EntryId.Novel(SURVIVOR)).readText() shouldBe "discarded"
    }

    @Test
    fun `an empty record leaves every cover alone`() = runTest {
        cover(EntryId.Manga(SURVIVOR)).writeText("survivor")

        run(FakeRecord())

        cover(EntryId.Manga(SURVIVOR)).readText() shouldBe "survivor"
    }

    /** MainActivity blocks the main thread on the migrations, and a folder merge copies every chapter. */
    @Test
    fun `the migration merges no download folder`() = runTest {
        run(FakeRecord(MergedDuplicate(ContentType.MANGA, DISCARDED, SURVIVOR, TITLE)))

        coVerify(exactly = 0) { downloads.carryFolders(any()) }
    }

    @Test
    fun `the migration keeps the record for the folder pass`() = runTest {
        val duplicate = MergedDuplicate(ContentType.MANGA, DISCARDED, SURVIVOR, TITLE)
        val record = FakeRecord(duplicate)

        run(record)

        record.getAll() shouldBe listOf(duplicate)
    }

    @Test
    fun `a record of merged chapters alone is emptied by the migration`() = runTest {
        val record = FakeRecord(chapters = listOf(MergedDuplicateChapter(ContentType.MANGA, 11L, 20L)))

        run(record)

        record.getChapters().shouldBeEmpty()
    }

    @Test
    fun `the folder pass empties the record once the folders are merged`() = runTest {
        val record = FakeRecord(MergedDuplicate(ContentType.MANGA, DISCARDED, SURVIVOR, TITLE))
        coEvery { downloads.carryFolders(any()) } returns true

        migration(record).carryFolders()

        record.getAll().shouldBeEmpty()
    }

    @Test
    fun `the folder pass keeps the record while a folder merge is unfinished`() = runTest {
        val duplicate = MergedDuplicate(ContentType.NOVELS, DISCARDED, SURVIVOR, TITLE)
        val record = FakeRecord(duplicate)
        coEvery { downloads.carryFolders(any()) } returns false

        migration(record).carryFolders()

        record.getAll() shouldBe listOf(duplicate)
    }

    /** Every launch reads the record, and building the carry starts a download index scan. */
    @Test
    fun `the folder pass with nothing left to merge does not build the download carry`() = runTest {
        var built = false

        MergedDuplicateCarryMigration(FakeRecord(), coverCache) { downloads.also { built = true } }
            .carryFolders()

        built shouldBe false
    }

    /** Once the migration has run a new entry may hold the freed id, and the cover under it is that entry's. */
    @ParameterizedTest
    @EnumSource(Type::class)
    fun `the folder pass leaves the covers alone`(type: Type) = runTest {
        cover(type.entry(DISCARDED)).writeText("new entry")
        coEvery { downloads.carryFolders(any()) } returns true

        migration(FakeRecord(MergedDuplicate(type.contentType, DISCARDED, SURVIVOR, TITLE))).carryFolders()

        cover(type.entry(DISCARDED)).readText() shouldBe "new entry"
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `running again over a record that was not emptied keeps the moved cover`(type: Type) = runTest {
        cover(type.entry(DISCARDED)).writeText("discarded")
        val record = FakeRecord(MergedDuplicate(type.contentType, DISCARDED, SURVIVOR, TITLE), clears = false)

        run(record)
        run(record)

        cover(type.entry(SURVIVOR)).readText() shouldBe "discarded"
    }

    /** The queue carry itself is pinned in MergedDuplicateDownloadsTest. */
    @Test
    fun `the download queues are re-pointed from the record`() = runTest {
        val duplicate = MergedDuplicate(ContentType.NOVELS, DISCARDED, SURVIVOR, TITLE)
        val chapter = MergedDuplicateChapter(ContentType.NOVELS, 11L, 20L)

        run(FakeRecord(duplicate, chapters = listOf(chapter)))

        coVerify { downloads.remapQueues(listOf(duplicate), listOf(chapter)) }
    }

    private suspend fun run(record: MergedDuplicateRepository) {
        migration(record).invoke(MigrationContext(dryrun = false, previousVersion = 185))
    }

    private fun migration(record: MergedDuplicateRepository) =
        MergedDuplicateCarryMigration(record, coverCache) { downloads }

    private fun cover(entryId: EntryId) = coverCache.getCustomCoverFile(entryId)

    enum class Type(val contentType: ContentType, val entry: (Long) -> EntryId) {
        MANGA(ContentType.MANGA, EntryId::Manga),
        NOVEL(ContentType.NOVELS, EntryId::Novel),
    }

    private class FakeRecord(
        vararg duplicates: MergedDuplicate,
        chapters: List<MergedDuplicateChapter> = emptyList(),
        private val clears: Boolean = true,
    ) : MergedDuplicateRepository {
        private val rows = duplicates.toMutableList()
        private val chapterRows = chapters.toMutableList()

        override suspend fun getAll() = rows.toList()

        override suspend fun getChapters() = chapterRows.toList()

        override suspend fun clear() {
            if (clears) {
                rows.clear()
                chapterRows.clear()
            }
        }
    }

    private companion object {
        const val DISCARDED = 3L
        const val SURVIVOR = 7L
        const val TITLE = "Title"
    }
}
