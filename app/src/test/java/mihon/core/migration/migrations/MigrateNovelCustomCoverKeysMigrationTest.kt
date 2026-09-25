package mihon.core.migration.migrations

import eu.kanade.tachiyomi.data.cache.CoverCache
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.core.migration.MigrationContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import java.io.File

class MigrateNovelCustomCoverKeysMigrationTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `a partial copy left by an earlier attempt is replaced by the user's cover`() = runTest {
        val legacy = File(dir, "legacy").apply { writeText("the whole cover") }
        val target = File(dir, "target").apply { writeText("the wh") }
        val coverCache = mockk<CoverCache> {
            every { getCustomCoverFile(-NOVEL_ID) } returns legacy
            every { getCustomCoverFile(EntryId.Novel(NOVEL_ID)) } returns target
        }
        val novels = mockk<NovelRepository> {
            coEvery { getAll() } returns listOf(Novel.create().copy(id = NOVEL_ID))
        }

        MigrateNovelCustomCoverKeysMigration(novels, coverCache)
            .invoke(MigrationContext(dryrun = false, previousVersion = 185))

        target.readText() shouldBe "the whole cover"
    }

    private companion object {
        const val NOVEL_ID = 4L
    }
}
