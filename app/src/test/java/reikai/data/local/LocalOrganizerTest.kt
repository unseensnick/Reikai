package reikai.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import reikai.domain.chapter.NoLocalVolumes
import reikai.domain.library.ContentType
import reikai.domain.local.GatedLocalFileJournal
import reikai.domain.local.InMemoryLocalFileJournal
import reikai.domain.local.LocalFileJournalPlan.FileStep
import reikai.domain.local.LocalFileJournalPlan.Organize
import reikai.domain.local.LocalSeriesLocks
import reikai.storage.FakeSafProvider
import java.io.File

/**
 * An organize's journal always names its files where they are, and a run refuses its series only when its files
 * and rows may disagree, for both content types, on the fake picked-folder provider. The organizes of real series,
 * with their rows, are LocalMovesUnderLockTest's and LocalNovelVolumesTest's.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class LocalOrganizerTest(private val type: ContentType) {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val locks = LocalSeriesLocks(NoLocalVolumes).apply { open() }
    private val journal = InMemoryLocalFileJournal()

    // Retries run on a clock no test here advances, so a refused series stays as its first run left it.
    private val organizer =
        LocalOrganizer(
            context,
            journal,
            mockk(),
            locks,
            CoroutineScope(StandardTestDispatcher(TestCoroutineScheduler())),
        )
    private lateinit var local: File

    @Before
    fun setUp() {
        FakeSafProvider.install(context, tmp.root)
        local = tmp.newFolder("local")
        File(local, "From").mkdirs()
        File(local, "From/page.jpg").writeBytes(byteArrayOf(1))
    }

    @After
    fun tearDown() {
        FakeSafProvider.onCall = null
    }

    private fun renaming(from: String, to: String) = Organize(
        entryId = 1L,
        root = FakeSafProvider.picked(context, local).uri.toString(),
        fromSeries = from,
        toSeries = to,
        volumeKeys = emptyMap(),
        urls = emptyList(),
        ownersBefore = emptyList(),
        ownersAfter = emptyList(),
        steps = listOf(FileStep.Rename(listOf(from), to)),
    )

    /** Another writer takes the new name, then the old one while the provider puts the folder back: a third name. */
    @Test
    fun `a rename put back onto a name another writer took leaves the journal holding the folder where it is`() =
        runTest {
            val squat = mutableSetOf("To", "From")
            FakeSafProvider.onCall = { call, target ->
                if (call == "renameDocument" && squat.remove(target.name) && !target.exists()) target.mkdir()
            }

            organizer.run(type, renaming("From", "To"))

            val held = (journal.entries.single().plan as Organize).steps.flatMap { (it as FileStep.Rename).path }
            listOf(File(local, "From (1)/page.jpg").exists(), held, locks.isBlocked(type, listOf("From (1)"))) shouldBe
                listOf(true, listOf("From (1)"), true)
        }

    private fun movingVolume() = renaming("From", "To").copy(
        steps = listOf(FileStep.Move(listOf("From", "Vol 1"), listOf("To"))),
    )

    /** Another writer takes the target's name, then the volume's own while the provider moves it back: a third name. */
    @Test
    fun `a move put back onto a name another writer took leaves the journal holding the volume where it is`() =
        runTest {
            File(local, "From/Vol 1").mkdirs()
            File(local, "To").mkdirs()
            FakeSafProvider.moveDodgesTakenName = true
            val squat = mutableSetOf(File(local, "To/Vol 1"), File(local, "From/Vol 1"))
            FakeSafProvider.onCall = { call, target ->
                if (call == "moveDocument" || call == "renameDocument") {
                    if (squat.remove(target)) target.mkdir()
                }
            }

            organizer.run(type, movingVolume())

            val held = (journal.entries.single().plan as Organize).steps.map { (it as FileStep.Rename).path }
            listOf(File(local, "From/Vol 1 (1)").isDirectory, held, locks.isBlocked(type, listOf("From"))) shouldBe
                listOf(true, listOf(listOf("From", "Vol 1 (1)")), true)
        }

    /** The provider dodged the move's name and then failed the move back, so the volume waits in the target. */
    @Test
    fun `a move left in the target under a dodged name is put back where it came from`() = runTest {
        File(local, "From/Vol 1").mkdirs()
        File(local, "To").mkdirs()
        FakeSafProvider.moveDodgesTakenName = true
        var moves = 0
        FakeSafProvider.onCall = { call, target ->
            if (call == "moveDocument" && ++moves == 1) target.mkdir()
            if (call == "moveDocument" && moves == 2) error("provider died")
        }

        organizer.run(type, movingVolume())

        listOf(File(local, "From/Vol 1").isDirectory, File(local, "To").list()!!.toList(), journal.entries) shouldBe
            listOf(true, listOf("Vol 1"), emptyList<Any>())
    }

    /** The run read the folder before its journal row; one later query failing must not strand a row with nothing done. */
    @Test
    fun `an organize whose local folder fails a query after the first look refuses no folder`() = runTest {
        var rootQueries = 0
        FakeSafProvider.onCall = { call, target ->
            if (call == "queryDocument" && target.absoluteFile == local.absoluteFile && ++rootQueries == 2) {
                error("provider died")
            }
        }

        organizer.run(type, renaming("From", "To"))

        (journal.entries.isEmpty() to locks.isBlocked(type, listOf("From", "To"))) shouldBe (true to false)
    }

    /** A retry that read the row just before Forget took the lock must not replay what Forget dropped. */
    @Test
    fun `a retry that read its row before Forget moves no file once Forget drops the row`() = runTest {
        File(local, "To").mkdirs()
        val retries = TestCoroutineScheduler()
        val gated = GatedLocalFileJournal(journal)
        val organizer = LocalOrganizer(context, gated, mockk(), locks, CoroutineScope(StandardTestDispatcher(retries)))
        val plan = renaming("From", "To")
        val id = journal.insert(type, plan)
        // Both names there, so the cut rename cannot be told landed and the row is held for a retry.
        organizer.replay(type, id, plan)
        File(local, "To").delete()
        val (read, release) = gated.holdNextRead()
        retries.advanceTimeBy(organizer.firstRetry.inWholeMilliseconds + 1)
        read.await()

        organizer.forget(type, listOf("From"))
        release.complete(Unit)
        retries.advanceUntilIdle()

        File(local, "To").exists() shouldBe false
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun types() = listOf(ContentType.MANGA, ContentType.NOVELS)
    }
}
