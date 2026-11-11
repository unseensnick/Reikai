package reikai.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import reikai.domain.chapter.ChapterTombstoneRepository
import reikai.domain.chapter.NoChapterTombstoneRepository
import reikai.domain.chapter.SyncedChapter
import reikai.domain.library.ContentType
import reikai.domain.local.ChapterMove
import reikai.domain.local.InMemoryLocalFileJournal
import reikai.domain.local.LocalChapterMoveRepository
import reikai.domain.local.LocalFileJournalPlan
import reikai.domain.local.LocalSeriesLocks
import reikai.domain.local.OwnerMoves
import reikai.domain.local.SeriesAddress
import reikai.domain.local.SeriesRename
import reikai.domain.local.VolumeRowMove
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.model.NovelChapter
import reikai.novel.local.LocalNovelSource
import reikai.novel.local.epub.EpubChapterKey
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import java.io.File
import kotlin.time.Instant

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

    /** The replay over rows at the old address, as a kill before the row step leaves them. */
    private fun replay() = LocalFileJournalReplay(
        context,
        journal,
        locks,
        moves,
        mockk<ChapterRepository> {
            coEvery { getChapterByMangaId(1) } returns listOf(Chapter.create().copy(url = url("Series", volume)))
        },
        mockk<NovelChapterRepository> {
            coEvery { getByNovelId(1) } returns listOf(novelChapter(url("Series", volume)))
        },
        // A chapter of the volume whose file a refresh had lost, kept as a tombstone with no row.
        object : ChapterTombstoneRepository by NoChapterTombstoneRepository {
            override suspend fun byOwner(type: ContentType, ownerId: Long) =
                listOf(SyncedChapter(-1L, lostUrl("Series", volume), 2.0))
        },
    )

    private fun lostUrl(series: String, volume: String) = if (type == ContentType.NOVELS) {
        LocalNovelSource.chapterPath(series, volume, EpubChapterKey("ch2.xhtml"))
    } else {
        "$series/$volume/b.cbz"
    }

    private fun uni(file: File) = UniFile.fromFile(file)!!

    private fun renaming(series: LocalFileNames.Rename?) = JournaledRename(
        journal,
        type,
        1,
        uni(root),
        uni(File(root, "Series")),
        series,
        listOf(LocalFileNames.Rename(volume, renamedVolume)),
    )

    @Test
    fun `a rename killed after its disk step gets its row step at the next start`() = runTest {
        renaming(LocalFileNames.Rename("Series", "Saga")).renameOnDisk()

        replay().replay()

        listOf(moves.renames, journal.entries, files(), locks.isOpen) shouldBe listOf(
            listOf(
                Triple(
                    type,
                    SeriesRename(1, "Series", "Saga", mapOf(volume to renamedVolume)),
                    listOf(
                        ChapterMove(url("Series", volume), url("Saga", renamedVolume)),
                        ChapterMove(lostUrl("Series", volume), lostUrl("Saga", renamedVolume)),
                    ),
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
        runCatching { renaming(LocalFileNames.Rename("Series", "Saga")).renameOnDisk() }

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

    /** Records each rename's arguments and, as the real one does, takes the journal row in the same step. */
    private inner class RecordingChapterMoves : LocalChapterMoveRepository {
        val renames = mutableListOf<Triple<ContentType, SeriesRename, List<ChapterMove>>>()

        override suspend fun rename(
            type: ContentType,
            rename: SeriesRename,
            chapters: List<ChapterMove>,
            journalId: Long?,
        ) {
            renames += Triple(type, rename, chapters)
            journalId?.let { journal.delete(it) }
        }

        override suspend fun move(
            type: ContentType,
            address: SeriesAddress?,
            chapters: List<OwnerMoves>,
            volumes: List<VolumeRowMove>,
        ) = error("not a move")

        override suspend fun forgetVolumes(
            type: ContentType,
            entryId: Long,
            series: String,
            volumes: Map<String, String>,
        ) = error("not a delete")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun types() = listOf(ContentType.MANGA, ContentType.NOVELS)

        fun novelChapter(url: String) =
            NovelChapter(10, 1, url, "C", true, false, 0, 1.0, 0, Instant.fromEpochMilliseconds(0), null, "")
    }
}
