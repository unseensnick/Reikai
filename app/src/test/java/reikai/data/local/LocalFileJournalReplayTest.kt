package reikai.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hippo.unifile.UniFile
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.beInstanceOf
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
import reikai.domain.chapter.NoLocalVolumes
import reikai.domain.library.ContentType
import reikai.domain.local.InMemoryLocalFileJournal
import reikai.domain.local.LocalFileJournalPlan
import reikai.domain.local.LocalSeriesBlockedException
import reikai.domain.local.LocalSeriesLocks
import reikai.storage.FakeSafProvider
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
    private val locks = LocalSeriesLocks(NoLocalVolumes)
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

    /** Another writer took the name meanwhile, which the replace leaves for its row: each book lists at a free name. */
    @Test
    fun `a replace whose name another writer took meanwhile loses neither the old book nor the copy`() = runTest {
        journaled()
        old.renameTo(aside)
        File(old.parentFile, old.name).writeText("theirs")

        replay().replay()

        old.parentFile.listFiles()!!.map { it.name to it.readText() }.sortedBy { it.second } shouldBe listOf(
            old.name.replace(".", " (3).") to "newer",
            old.name.replace(".", " (2).") to "older",
            old.name to "theirs",
        )
    }

    @Test
    fun `a replace whose folder cannot be reached keeps its row for a later start`() = runTest {
        val id = journaled(folder = uri(File(old.parentFile.parentFile, "Gone")))

        replay().replay()

        journal.entries.map { it.id } shouldBe listOf(id)
    }

    /** Left as it is, the old book is in no listing, so the series waits on a repair as an organize's does. */
    @Test
    fun `a replace that cannot be finished at start holds its series for a repair`() = runTest {
        FakeSafProvider.install(context, tmp.root)
        val folder = FakeSafProvider.picked(context, old.parentFile).uri.toString()
        journal.insert(type, LocalFileJournalPlan.Replace(folder, old.name, aside.name, part.name, uri(part)))
        old.renameTo(aside)
        part.delete()
        FakeSafProvider.onCall = { call, _ -> if (call == "renameDocument") error("no") }

        replay().replay()
        FakeSafProvider.onCall = null

        runCatching { locks.withSeries(type, "Series") {} }.exceptionOrNull() should
            beInstanceOf<LocalSeriesBlockedException>()
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun types() = listOf(ContentType.MANGA, ContentType.NOVELS)
    }
}
