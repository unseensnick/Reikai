package reikai.data.local

import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.domain.local.LocalFileJournalEntry
import reikai.domain.local.LocalFileJournalPlan
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/** A journal row reads back as the plan that was written, through every update, and goes when deleted. */
class LocalFileJournalRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var journal: LocalFileJournalRepositoryImpl

    @BeforeEach
    fun setUp() = runTest {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        database = DatabaseBindings.providesDatabase(driver)
        journal = LocalFileJournalRepositoryImpl(database)
    }

    @AfterEach
    fun tearDown() = driver.close()

    @Test
    fun `a plan reads back as updated, beside another, in the order written`() = runTest {
        val first = LocalFileJournalPlan.Replace("file:///local/Series", "a.cbz", "a.cbz.old", "a.cbz_tmp", "p")
        val second = first.copy(name = "b.cbz")
        val id = journal.insert(ContentType.MANGA, first)
        journal.insert(ContentType.NOVELS, second)
        val updated = first.copy(aside = "a (2).cbz.old")

        journal.update(id, updated)

        journal.all() shouldBe listOf(
            LocalFileJournalEntry(id, ContentType.MANGA, updated),
            LocalFileJournalEntry(id + 1, ContentType.NOVELS, second),
        )
    }

    @Test
    fun `a deleted row is gone`() = runTest {
        val id = journal.insert(ContentType.MANGA, LocalFileJournalPlan.Replace("f", "n", "a", "p", "u"))

        journal.delete(id)

        journal.all() shouldBe emptyList()
    }

    @Test
    fun `a replace written before its series name was kept reads back without one`() = runTest {
        database.local_file_journalQueries.insert(
            0L,
            """{"type":"replace","folder":"f","name":"n","aside":"a","part":"p","partUri":"u"}""",
        ).awaitAsOne()

        journal.all().map { it.plan } shouldBe listOf(LocalFileJournalPlan.Replace("f", "n", "a", "p", "u", null))
    }

    /** A row from a newer build, read by an older one after a downgrade, must not stop every other row's replay. */
    @Test
    fun `a row this build cannot read is passed over and the rest still read`() = runTest {
        database.local_file_journalQueries.insert(0L, """{"type":"future"}""").awaitAsOne()
        val replace = LocalFileJournalPlan.Replace("f", "n", "a", "p", "u")
        val id = journal.insert(ContentType.MANGA, replace)

        journal.all() shouldBe listOf(LocalFileJournalEntry(id, ContentType.MANGA, replace))
    }
}
