package reikai.data.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.domain.local.LocalFileJournalEntry
import reikai.domain.local.LocalFileJournalPlan
import reikai.domain.local.LocalFileJournalPlan.Step
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/** A journal row reads back as the plan that was written, through every update, and goes when deleted. */
class LocalFileJournalRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var journal: LocalFileJournalRepositoryImpl

    @BeforeEach
    fun setUp() = runTest {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        journal = LocalFileJournalRepositoryImpl(DatabaseBindings.providesDatabase(driver))
    }

    @AfterEach
    fun tearDown() = driver.close()

    @Test
    fun `a rename plan reads back as updated, beside a replace plan, in the order written`() = runTest {
        val rename = LocalFileJournalPlan.Rename(
            parent = "file:///local",
            folder = "Series",
            entryId = 1,
            series = Step("Series", "Saga", "file:///local/Series"),
            volumes = listOf(Step("Vol 1", "Vol 2", "file:///local/Series/Vol%201")),
        )
        val replace = LocalFileJournalPlan.Replace("file:///local/Series", "a.cbz", "a.cbz.old", "a.cbz.part", "o", "p")
        val id = journal.insert(ContentType.MANGA, rename)
        journal.insert(ContentType.NOVELS, replace)
        val took = rename.copy(volumes = listOf(rename.volumes.single().copy(toUri = "file:///local/Series/Vol%202")))

        journal.update(id, took)

        journal.all() shouldBe listOf(
            LocalFileJournalEntry(id, ContentType.MANGA, took),
            LocalFileJournalEntry(id + 1, ContentType.NOVELS, replace),
        )
    }

    @Test
    fun `a deleted row is gone`() = runTest {
        val id = journal.insert(ContentType.MANGA, LocalFileJournalPlan.Replace("f", "n", "a", "p", "o", "u"))

        journal.delete(id)

        journal.all() shouldBe emptyList()
    }
}
