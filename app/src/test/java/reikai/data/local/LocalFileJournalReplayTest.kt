package reikai.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import reikai.domain.library.ContentType
import reikai.domain.local.InMemoryLocalFileJournal
import reikai.domain.local.LocalFileJournalPlan
import reikai.domain.local.LocalSeriesLocks
import java.io.File

/**
 * An import Replace killed between its old book stepping aside and its copy taking the name is finished or put
 * back at the next start, with no file lost, for both content types; one whose folder cannot be reached waits for
 * a later start. Organize files' own replay is LocalOrganizer's, pinned in LocalMovesUnderLockTest.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class LocalFileJournalReplayTest(private val type: ContentType) {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val locks = LocalSeriesLocks()
    private val journal = InMemoryLocalFileJournal()
    private lateinit var old: File
    private lateinit var part: File
    private lateinit var aside: File

    @Before
    fun setUp() {
        val book = if (type == ContentType.NOVELS) "Series - Vol 1.epub" else "Vol 1.cbz"
        old = File(tmp.newFolder("local", "Series"), book).apply { writeText("older") }
        part = File(old.parentFile, "$book.part").apply { writeText("newer") }
        aside = File(old.parentFile, "$book.old")
    }

    private fun replay() = LocalFileJournalReplay(
        context,
        journal,
        locks,
        LocalOrganizer(context, journal, mockk(), locks, CoroutineScope(Dispatchers.Unconfined)),
    )

    private fun uri(file: File) = UniFile.fromFile(file)!!.uri.toString()

    private suspend fun journaled(folder: String = uri(old.parentFile)) = journal.insert(
        type,
        LocalFileJournalPlan.Replace(folder, old.name, aside.name, part.name, uri(part)),
    )

    private fun folder() = old.parentFile.list()!!.sorted() to old.readText()

    @Test
    fun `a replace killed with the old book stepped aside promotes the whole copy`() = runTest {
        journaled()
        old.renameTo(aside)

        replay().replay()

        (folder() to journal.entries) shouldBe ((listOf(old.name) to "newer") to emptyList<Any>())
    }

    @Test
    fun `a replace killed before the old book stepped aside keeps it and drops the copy`() = runTest {
        journaled()

        replay().replay()

        (folder() to journal.entries) shouldBe ((listOf(old.name) to "older") to emptyList<Any>())
    }

    @Test
    fun `a replace killed with only the old book left aside puts it back`() = runTest {
        journaled()
        old.renameTo(aside)
        part.delete()

        replay().replay()

        (folder() to journal.entries) shouldBe ((listOf(old.name) to "older") to emptyList<Any>())
    }

    @Test
    fun `a replace whose folder cannot be reached keeps its row for a later start`() = runTest {
        val id = journaled(folder = uri(File(old.parentFile.parentFile, "Gone")))

        replay().replay()

        journal.entries.map { it.id } shouldBe listOf(id)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun types() = listOf(ContentType.MANGA, ContentType.NOVELS)
    }
}
