package reikai.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import reikai.domain.library.ContentType
import reikai.domain.local.InMemoryLocalFileJournal
import reikai.domain.local.LocalChapterMoveRepository
import reikai.domain.local.LocalFileJournalPlan
import reikai.domain.local.LocalFileJournalPlan.Step
import reikai.domain.local.LocalSeriesLocks
import reikai.domain.local.SeriesRename
import reikai.domain.local.VolumeOwner
import reikai.domain.local.VolumeRowMove
import reikai.novel.local.LocalNovelSource
import reikai.novel.local.epub.EpubChapterKey
import java.io.File

/**
 * A Rename files or import Replace killed between its disk step and its row step is finished or put back at
 * the next start, with the chapter rows moved to where the files are and no file lost, for both content types.
 * The row step itself is LocalChapterMoveRepositoryImpl's, pinned in its own test with the journal row.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class LocalFileJournalReplayTest(private val type: ContentType) {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var root: File
    private val locks = LocalSeriesLocks()
    private val journal = InMemoryLocalFileJournal()
    private val moves = RecordingChapterMoves()

    private val volume get() = if (type == ContentType.NOVELS) "Series - Vol 1.epub" else "Vol 1"
    private val renamedVolume get() = if (type == ContentType.NOVELS) "Saga - Vol 1.epub" else "Vol 2"
    private val chapterFile get() = if (type == ContentType.NOVELS) "" else "/a.cbz"

    private fun url(series: String, volume: String) = if (type == ContentType.NOVELS) {
        LocalNovelSource.chapterPath(series, volume, EpubChapterKey("ch1.xhtml"))
    } else {
        "$series/$volume/a.cbz"
    }

    @Before
    fun setUp() {
        root = tmp.newFolder("local")
        File(root, "Series/$volume$chapterFile").apply { parentFile!!.mkdirs() }.writeText("content")
    }

    private fun files() = root.walkTopDown().filter { it.isFile }
        .map { it.relativeTo(root).path.replace('\\', '/') }.sorted().toList()

    private fun replay() = LocalFileJournalReplay(
        context,
        journal,
        locks,
        moves,
        LocalOrganizer(context, journal, moves, locks),
    )

    /** A chapter of the volume whose file a refresh had lost, kept with no row, which the row step moves too. */
    private fun lostUrl(series: String, volume: String) = if (type == ContentType.NOVELS) {
        LocalNovelSource.chapterPath(series, volume, EpubChapterKey("ch2.xhtml"))
    } else {
        "$series/$volume/b.cbz"
    }

    private fun uni(file: File) = UniFile.fromFile(file)!!

    /**
     * The disk step an older build's Rename files wrote down before its row step: the plan first, then the volume
     * renamed and then the series folder, each noted as it takes, stopping at the first rename that fails.
     */
    private suspend fun renamedByAnOlderBuild(seriesTo: String) {
        val seriesFile = uni(File(root, "Series"))
        val volumeFile = seriesFile.findFile(volume)!!
        var plan = LocalFileJournalPlan.Rename(
            parent = uni(root).uri.toString(),
            folder = "Series",
            entryId = 1,
            series = Step("Series", seriesTo, seriesFile.uri.toString()),
            volumes = listOf(Step(volume, renamedVolume, volumeFile.uri.toString())),
        )
        val id = journal.insert(type, plan)
        check(volumeFile.renameTo(renamedVolume))
        plan = plan.copy(volumes = plan.volumes.map { it.copy(toUri = volumeFile.uri.toString()) })
        journal.update(id, plan)
        check(!File(root, seriesTo).exists() && seriesFile.renameTo(seriesTo))
        journal.update(id, plan.copy(series = plan.series?.copy(toUri = seriesFile.uri.toString())))
    }

    @Test
    fun `a rename killed after its disk step gets its row step at the next start`() = runTest {
        renamedByAnOlderBuild("Saga")

        replay().replay()

        listOf(moves.renames, journal.entries, files(), locks.isOpen) shouldBe listOf(
            listOf(
                Triple(
                    type,
                    SeriesRename(1, "Series", "Saga", mapOf(volume to renamedVolume)),
                    listOf(url("Saga", renamedVolume), lostUrl("Saga", renamedVolume)),
                ),
            ),
            emptyList<Any>(),
            listOf("Saga/$renamedVolume$chapterFile"),
            true,
        )
    }

    @Test
    fun `a rename killed with its series folder still to go is put back, rows untouched`() = runTest {
        // The series' new name is taken, so the volume renames and the series folder's rename fails; a kill
        // there leaves that disk state without the rename back the live path does.
        File(root, "Saga").mkdirs()
        runCatching { renamedByAnOlderBuild("Saga") }

        replay().replay()

        listOf(moves.renames, journal.entries, files()) shouldBe
            listOf(emptyList<Any>(), emptyList<Any>(), listOf("Series/$volume$chapterFile"))
    }

    @Test
    fun `a replace killed with the old book stepped aside promotes the whole copy`() = runTest {
        val old = File(root, "Series/$volume$chapterFile")
        val part = File(old.parentFile, "${old.name}.part").apply { writeText("newer") }
        journal.insert(
            type,
            LocalFileJournalPlan.Replace(
                folder = uni(old.parentFile).uri.toString(),
                name = old.name,
                aside = "${old.name}.old",
                part = part.name,
                old = uni(old).uri.toString(),
                partUri = uni(part).uri.toString(),
            ),
        )
        old.renameTo(File(old.parentFile, "${old.name}.old"))

        replay().replay()

        Triple(old.parentFile.list()!!.sorted(), old.readText(), journal.entries) shouldBe
            Triple(listOf(old.name), "newer", emptyList<Any>())
    }

    /**
     * Records each rename with where its mapping sends the row's and the lost chapter's address and, as the
     * real one does, takes the journal row in the same step.
     */
    private inner class RecordingChapterMoves : LocalChapterMoveRepository {
        val renames = mutableListOf<Triple<ContentType, SeriesRename, List<String>>>()

        override suspend fun rename(
            type: ContentType,
            rename: SeriesRename,
            journalId: Long?,
            movedUrl: (String) -> String,
        ) {
            renames += Triple(type, rename, listOf(url("Series", volume), lostUrl("Series", volume)).map(movedUrl))
            journalId?.let { journal.delete(it) }
        }

        override suspend fun regroup(
            type: ContentType,
            series: Map<String, Long>,
            remove: List<VolumeOwner>,
            add: List<VolumeOwner>,
            volumes: List<VolumeRowMove>,
            unitOf: (String) -> Pair<String, String>?,
        ) = error("not a regroup")

        override suspend fun organize(
            type: ContentType,
            rename: SeriesRename,
            remove: List<VolumeOwner>,
            add: List<VolumeOwner>,
            inTransaction: suspend () -> Unit,
            movedUrl: (String) -> String,
        ) = error("not an organize")

        override suspend fun forgetVolumes(
            type: ContentType,
            entryId: Long,
            series: String,
            volumes: Map<String, String>,
            owners: List<VolumeOwner>,
        ) = error("not a delete")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun types() = listOf(ContentType.MANGA, ContentType.NOVELS)
    }
}
