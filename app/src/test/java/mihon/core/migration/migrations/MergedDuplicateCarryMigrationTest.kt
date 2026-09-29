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

    @Test
    fun `the record is emptied once it has run`() = runTest {
        val record = FakeRecord(MergedDuplicate(ContentType.MANGA, DISCARDED, SURVIVOR, TITLE))
        coEvery { downloads.carry(any(), any()) } returns true

        run(record)

        record.getAll().shouldBeEmpty()
    }

    @Test
    fun `the record is kept while a download folder merge is unfinished`() = runTest {
        val duplicate = MergedDuplicate(ContentType.MANGA, DISCARDED, SURVIVOR, TITLE)
        val record = FakeRecord(duplicate)
        coEvery { downloads.carry(any(), any()) } returns false

        run(record)

        record.getAll() shouldBe listOf(duplicate)
    }

    @Test
    fun `a later launch empties the record once the folders are merged`() = runTest {
        val record = FakeRecord(MergedDuplicate(ContentType.MANGA, DISCARDED, SURVIVOR, TITLE))
        coEvery { downloads.carryFolders(any()) } returns true

        migration(record).retryUnfinishedFolders()

        record.getAll().shouldBeEmpty()
    }

    @Test
    fun `a later launch keeps the record while a folder merge is still unfinished`() = runTest {
        val duplicate = MergedDuplicate(ContentType.NOVELS, DISCARDED, SURVIVOR, TITLE)
        val record = FakeRecord(duplicate)
        coEvery { downloads.carryFolders(any()) } returns false

        migration(record).retryUnfinishedFolders()

        record.getAll() shouldBe listOf(duplicate)
    }

    /** Every launch reads the record, and building the carry starts a download index scan. */
    @Test
    fun `a later launch with nothing left to merge does not build the download carry`() = runTest {
        var built = false

        MergedDuplicateCarryMigration(FakeRecord(), coverCache) { downloads.also { built = true } }
            .retryUnfinishedFolders()

        built shouldBe false
    }

    /** By a later launch a new entry may hold the freed id, and the cover under it is that entry's. */
    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a later launch leaves the covers alone`(type: Type) = runTest {
        cover(type.entry(DISCARDED)).writeText("new entry")
        coEvery { downloads.carryFolders(any()) } returns true

        migration(FakeRecord(MergedDuplicate(type.contentType, DISCARDED, SURVIVOR, TITLE))).retryUnfinishedFolders()

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

    /** The record reaches the download carry before it is emptied; that carry is pinned in MergedDuplicateDownloadsTest. */
    @Test
    fun `the downloads are carried from the record`() = runTest {
        val duplicate = MergedDuplicate(ContentType.NOVELS, DISCARDED, SURVIVOR, TITLE)
        val chapter = MergedDuplicateChapter(ContentType.NOVELS, 11L, 20L)

        run(FakeRecord(duplicate, chapters = listOf(chapter)))

        coVerify { downloads.carry(listOf(duplicate), listOf(chapter)) }
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
