package reikai.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import reikai.domain.chapter.LocalVolume
import reikai.domain.chapter.LooseFiles
import reikai.domain.library.ContentType
import reikai.domain.local.VolumeOwner
import reikai.util.resetVirtualDispatchers
import reikai.util.restubDefaultDispatcher
import reikai.util.virtualDispatchers

/**
 * What a combine, its Undo and a restore do to volume rows (saved order, names and notes), one case for manga and
 * novels each, through the real engine on both. Loose files cases are manga's only: a novel has no loose files.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class CombineVolumeNotesConformanceTest(private val type: ContentType) {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var world: CombineWorld

    @Before
    fun setUp() {
        virtualDispatchers()
        world = if (type == ContentType.NOVELS) NovelCombineWorld(context) else MangaCombineWorld(context)
        runTest { world.open(tmp, mapOf(KEPT to "Kept", OTHER to "Other", THIRD to "Third")) }
        restubDefaultDispatcher()
    }

    @After
    fun tearDown() {
        runTest { world.close() }
        resetVirtualDispatchers()
    }

    @Test
    fun `a combine shows each series' notes under its own volumes, keeps the series' own, and Undo takes them off`() =
        runTest {
            world.setSeriesNotes(KEPT, "Kept notes")
            world.setSeriesNotes(OTHER, "Other notes")
            val move = combined()
            val combined = world.notes("Kept") to world.seriesNotes(KEPT)

            world.onApp { move.undo() }

            (combined to world.notes("Kept")) shouldBe (
                (mapOf(keptVolume(1) to "Kept notes", otherVolume to "Other notes") to "Kept notes") to emptyMap()
                )
        }

    @Test
    fun `a combine keeps a volume's own notes and Undo takes the copied ones back off`() = runTest {
        world.setSeriesNotes(KEPT, "Kept notes")
        world.setSeriesNotes(OTHER, "Other notes")
        world.volumes.setNotes(type, "Kept", keptVolume(1), "Own")
        val move = combined()
        val combined = world.notes("Kept")

        world.onApp { move.undo() }

        (combined to world.notes("Kept")) shouldBe
            (mapOf(keptVolume(1) to "Own", otherVolume to "Other notes") to mapOf(keptVolume(1) to "Own"))
    }

    @Test
    fun `Undo carries a copied note the user edited since home to the volume's own series`() = runTest {
        world.setSeriesNotes(OTHER, "Other notes")
        val move = combined()
        world.volumes.setNotes(type, "Kept", otherVolume, "Edited")

        world.onApp { move.undo() }

        (world.notes("Other") to world.notes("Kept")) shouldBe
            (mapOf(world.volume("Other", 1) to "Edited") to emptyMap())
    }

    @Test
    fun `adding more volumes later does not refill a volume note emptied since`() = runTest {
        world.setSeriesNotes(KEPT, "Kept notes")
        combined()
        world.volumes.setNotes(type, "Kept", keptVolume(1), "")

        world.onApp { world.combine.combine(type, world.series(KEPT), listOf(world.series(THIRD))) }

        world.notes("Kept")[keptVolume(1)] shouldBe null
    }

    @Test
    fun `a volume of the kept folder coming home takes the notes of the series that held it`() = runTest {
        world.addVolume(KEPT, "Kept", 2)
        val split = world.onApp { world.combine.splitOut(type, world.series(KEPT), listOf(keptVolume(2)), "Split") }
        world.setSeriesNotes(KEPT, "Kept notes")
        world.setSeriesNotes(split.created!!, "Split notes")

        world.onApp { world.combine.combine(type, world.series(KEPT), listOf(world.series(split.created!!))) }

        world.notes("Kept") shouldBe mapOf(keptVolume(1) to "Kept notes", keptVolume(2) to "Split notes")
    }

    @Test
    fun `an Undo that fails puts the copied notes back`() = runTest {
        world.setSeriesNotes(OTHER, "Other notes")
        val move = combined()
        val combined = world.notes("Kept")
        world.changeOwnersBehindUndo()

        val undone = world.onApp { move.undo() }

        ((undone is LocalMoveResult.Failed) to world.notes("Kept")) shouldBe (true to combined)
    }

    @Test
    fun `a notes copy that fails partway keeps the Undo of the copies written before it`() = runTest {
        world.setSeriesNotes(KEPT, "Kept notes")
        world.setSeriesNotes(OTHER, "Other notes")
        world.notesWrites.failNotesAfter = 1
        val move = combined()
        world.notesWrites.failNotesAfter = null

        val undone = world.onApp { move.undo() }

        ((undone is LocalMoveResult.Moved) to world.notes("Kept")) shouldBe (true to emptyMap())
    }

    @Test
    fun `a restored owner row brings the joining volume's name and notes to its new series`() = runTest {
        world.volumes.setLabel(type, "Other", world.volume("Other", 1), "Label")
        world.volumes.setNotes(type, "Other", world.volume("Other", 1), "Notes")

        restoreOwnerRow()

        world.rows("Kept")[otherVolume]?.let { it.label to it.notes } shouldBe ("Label" to "Notes")
    }

    @Test
    fun `a restored owner row keeps what the backup gave the volume's new key over the device's`() = runTest {
        world.volumes.setLabel(type, "Other", world.volume("Other", 1), "Label")
        world.volumes.setNotes(type, "Other", world.volume("Other", 1), "Device")
        world.volumes.restore(type, "Kept", listOf(LocalVolume(otherVolume, null, null, notes = "Backup")))

        restoreOwnerRow()

        world.rows("Kept")[otherVolume]?.let { it.label to it.notes } shouldBe ("Label" to "Backup")
    }

    @Test
    fun `a restore from before a combine shows the lent volume's notes under the kept series`() = runTest {
        combined().finish()
        world.volumes.restore(type, "Other", listOf(LocalVolume(world.volume("Other", 1), null, null, notes = "N")))

        world.onApp { world.combine.restoreOwners(type, emptyList(), listOf("Kept", "Other")) }

        (world.rows("Kept")[otherVolume]?.notes to world.rows("Other")) shouldBe ("N" to emptyMap())
    }

    @Test
    fun `each loose file of the kept folder coming home under Each its own takes its holder's notes`() = runTest {
        val manga = mangaOnly()
        manga.addLoose(KEPT, "Kept", "Ch 5")
        manga.addLoose(KEPT, "Kept", "Ch 6")
        world.volumes.setLooseFiles("Kept", LooseFiles.EACH_ITS_OWN)
        val split = world.onApp { world.combine.splitOut(type, world.series(KEPT), listOf(""), "Split") }
        world.setSeriesNotes(split.created!!, "Split notes")

        world.onApp { world.combine.combine(type, world.series(KEPT), listOf(world.series(split.created!!))) }

        world.notes("Kept") shouldBe mapOf("Ch 5" to "Split notes", "Ch 6" to "Split notes")
    }

    @Test
    fun `Undo carries an edited note on a loose file come home back to the series that held it`() = runTest {
        val manga = mangaOnly()
        manga.addLoose(KEPT, "Kept", "Ch 5")
        world.volumes.setLooseFiles("Kept", LooseFiles.EACH_ITS_OWN)
        val split = world.onApp { world.combine.splitOut(type, world.series(KEPT), listOf(""), "Split") }
        world.setSeriesNotes(split.created!!, "Split notes")
        val home =
            world.onApp { world.combine.combine(type, world.series(KEPT), listOf(world.series(split.created!!))) }
        world.volumes.setNotes(type, "Kept", "Ch 5", "Edited")

        world.onApp { home.undo() }

        (world.notes("Split") to world.notes("Kept")) shouldBe (mapOf("Kept/" to "Edited") to emptyMap())
    }

    @Test
    fun `Undo carries an edited note on a series' loose files to each one Each its own shows`() = runTest {
        val manga = mangaOnly()
        manga.addLoose(OTHER, "Other", "Ch 7")
        manga.addLoose(OTHER, "Other", "Ch 8")
        world.volumes.setLooseFiles("Other", LooseFiles.EACH_ITS_OWN)
        world.setSeriesNotes(OTHER, "Other notes")
        val move = combined()
        world.volumes.setNotes(type, "Kept", "Other/", "Edited")

        world.onApp { move.undo() }

        world.notes("Other") shouldBe mapOf("Ch 7" to "Edited", "Ch 8" to "Edited")
    }

    @Test
    fun `Undo leaves an edited note on loose files their series shows in no volume where it was written`() =
        runTest {
            val manga = mangaOnly()
            manga.addLoose(OTHER, "Other", "Ch 7")
            world.volumes.setLooseFiles("Other", LooseFiles.NO_VOLUME)
            world.setSeriesNotes(OTHER, "Other notes")
            val move = combined()
            world.volumes.setNotes(type, "Kept", "Other/", "Edited")

            world.onApp { move.undo() }

            (world.notes("Other") to world.notes("Kept")) shouldBe
                (emptyMap<String, String>() to mapOf("Other/" to "Edited"))
        }

    @Test
    fun `a split takes the rows of loose files Each its own showed as volumes, and Undo brings them back`() =
        runTest {
            val manga = mangaOnly()
            manga.addLoose(KEPT, "Kept", "Ch 5")
            world.volumes.setLooseFiles("Kept", LooseFiles.EACH_ITS_OWN)
            world.volumes.setLabel(type, "Kept", "Ch 5", "Prologue")
            val split = world.onApp { world.combine.splitOut(type, world.series(KEPT), listOf(""), "Split") }
            val taken = world.rows("Split").mapValues { it.value.label }

            world.onApp { split.undo() }

            (taken to world.rows("Kept")["Ch 5"]?.label) shouldBe (mapOf("Kept/Ch 5" to "Prologue") to "Prologue")
        }

    /** The combine's rows have landed by its [nth] owner read that shows them, so that read failing loses no move. */
    private suspend fun combinedFailingRead(nth: Int): LocalVolumeMove {
        var skip = nth
        world.notesWrites.failRead = { rows -> rows.any { it.folder != it.owner } && skip-- == 0 }
        return combined()
    }

    @Test
    fun `a combine whose chapter read fails after its rows moved reports moved and keeps its Undo`() = runTest {
        val move = combinedFailingRead(1)

        val undone = world.onApp { move.undo() }

        (move.result to undone) shouldBe (LocalMoveResult.Moved to LocalMoveResult.Moved)
    }

    /** With no record of the owners after it, the Undo cannot tell a change since, so it refuses. */
    @Test
    fun `a combine whose owner read fails after its rows moved reports moved`() = runTest {
        val move = combinedFailingRead(0)

        val undone = world.onApp { move.undo() }

        (move.result to (undone is LocalMoveResult.Failed)) shouldBe (LocalMoveResult.Moved to true)
    }

    private suspend fun combined() =
        world.onApp { world.combine.combine(type, world.series(KEPT), listOf(world.series(OTHER))) }

    private suspend fun restoreOwnerRow() = world.onApp {
        world.combine.restoreOwners(type, listOf(VolumeOwner("Other", VolumeOwner.WHOLE, "Kept")), emptyList())
    }

    private fun keptVolume(n: Int) = world.volume("Kept", n)

    private val otherVolume get() = "Other/${world.volume("Other", 1)}"

    /** Loose files is manga's only: a novel's every book is a volume, so a novel declares the case unsupported. */
    private fun mangaOnly(): MangaCombineWorld {
        assumeTrue("A novel has no loose files", type == ContentType.MANGA)
        return world as MangaCombineWorld
    }

    companion object {
        private const val KEPT = 1L
        private const val OTHER = 2L
        private const val THIRD = 3L

        @JvmStatic
        @Parameters(name = "{0}")
        fun types() = listOf(ContentType.MANGA, ContentType.NOVELS).map { arrayOf<Any>(it) }
    }
}
