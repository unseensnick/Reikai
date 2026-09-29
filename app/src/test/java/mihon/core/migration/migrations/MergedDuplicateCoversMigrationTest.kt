package mihon.core.migration.migrations

import android.content.Context
import eu.kanade.tachiyomi.data.cache.CoverCache
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.core.migration.MigrationContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.dedupe.MergedDuplicate
import reikai.domain.dedupe.MergedDuplicateRepository
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import java.io.File

class MergedDuplicateCoversMigrationTest {

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

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a merged-away copy's custom cover moves to a survivor without one`(type: Type) = runTest {
        cover(type.entry(DISCARDED)).writeText("discarded")

        run(FakeRecord(MergedDuplicate(type.contentType, DISCARDED, SURVIVOR)))

        cover(type.entry(SURVIVOR)).readText() shouldBe "discarded"
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `the survivor's own custom cover is kept over a merged-away copy's`(type: Type) = runTest {
        cover(type.entry(DISCARDED)).writeText("discarded")
        cover(type.entry(SURVIVOR)).writeText("survivor")

        run(FakeRecord(MergedDuplicate(type.contentType, DISCARDED, SURVIVOR)))

        cover(type.entry(SURVIVOR)).readText() shouldBe "survivor"
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `no cover file is left under a merged-away id`(type: Type) = runTest {
        cover(type.entry(DISCARDED)).writeText("discarded")
        cover(type.entry(SURVIVOR)).writeText("survivor")

        run(FakeRecord(MergedDuplicate(type.contentType, DISCARDED, SURVIVOR)))

        cover(type.entry(DISCARDED)).exists() shouldBe false
    }

    @Test
    fun `a merged-away novel's cover still under its pre-186 name moves to the survivor`() = runTest {
        // MigrateNovelCustomCoverKeysMigration re-keys only the novels still in the table after the merge
        coverCache.getCustomCoverFile(-DISCARDED).writeText("discarded")

        run(FakeRecord(MergedDuplicate(ContentType.NOVELS, DISCARDED, SURVIVOR)))

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
        val record = FakeRecord(MergedDuplicate(ContentType.MANGA, DISCARDED, SURVIVOR))

        run(record)

        record.getAll().shouldBeEmpty()
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `running again over a record that was not emptied keeps the moved cover`(type: Type) = runTest {
        cover(type.entry(DISCARDED)).writeText("discarded")
        val record = FakeRecord(MergedDuplicate(type.contentType, DISCARDED, SURVIVOR), clears = false)

        run(record)
        run(record)

        cover(type.entry(SURVIVOR)).readText() shouldBe "discarded"
    }

    private suspend fun run(record: MergedDuplicateRepository) {
        MergedDuplicateCoversMigration(record, coverCache)
            .invoke(MigrationContext(dryrun = false, previousVersion = 185))
    }

    private fun cover(entryId: EntryId) = coverCache.getCustomCoverFile(entryId)

    enum class Type(val contentType: ContentType, val entry: (Long) -> EntryId) {
        MANGA(ContentType.MANGA, EntryId::Manga),
        NOVEL(ContentType.NOVELS, EntryId::Novel),
    }

    private class FakeRecord(
        vararg duplicates: MergedDuplicate,
        private val clears: Boolean = true,
    ) : MergedDuplicateRepository {
        private val rows = duplicates.toMutableList()

        override suspend fun getAll() = rows.toList()

        override suspend fun clear() {
            if (clears) rows.clear()
        }
    }

    private companion object {
        const val DISCARDED = 3L
        const val SURVIVOR = 7L
    }
}
