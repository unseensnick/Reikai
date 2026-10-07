package reikai.presentation.recents

import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.entry.EntryId
import reikai.domain.merge.ChapterUnit
import reikai.domain.reader.ChapterProgress

/**
 * A resolved target projected into the row it draws, the one projection both adapters make. Source A
 * (entry 100) holds chapters 1 and 2, source B (entry 200) their copies 11 and 12; A's chapter 5 is one
 * the stitch places nowhere. Entry 300 is an owner the projection cannot find, as a deleted member is.
 */
class RecentsTargetRowTest {

    private data class Copy(val id: Long, val owner: Long, val read: Boolean = false, val bookmark: Boolean = false)

    private val stitch = listOf(
        ChapterUnit(1L, 0, 0),
        ChapterUnit(11L, 0, 1),
        ChapterUnit(2L, 1, 0),
        ChapterUnit(12L, 1, 1),
    )

    private suspend fun row(
        lane: RecentsLane,
        group: List<Copy>,
        pooled: List<Copy> = group,
        progress: ChapterProgress = ChapterProgress.Pages(3, 10),
        onDisk: Set<Long> = emptySet(),
    ): RecentsTargetRow? = resolveRecentsTarget(
        lane = lane,
        group = group,
        pooled = pooled,
        stitch = stitch,
        ownSource = { emptyList() },
        id = { it.id },
        read = { it.read },
        bookmark = { it.bookmark },
        isHidden = { false },
    )?.toTargetRow(
        lane,
        id = { it.id },
        project = { copies ->
            copies.filter { it.owner != UNKNOWN_OWNER }.associate { copy ->
                copy.id to RecentsTargetCopy(
                    owner = EntryId.Manga(copy.owner),
                    ownerTitle = "t${copy.owner}",
                    ownerSource = "${copy.owner}",
                    name = "c${copy.id}",
                    number = copy.id.toDouble(),
                    scanlator = null,
                    url = "/${copy.id}",
                    read = copy.read,
                    bookmark = copy.bookmark,
                    progress = progress,
                )
            }
        },
    ) { chapterId, copies ->
        recentsCopiesDownloadUi(lane, chapterId, copies, { null }, RecentsDownloadProgress.Unsupported) {
            it.copy.chapterId in onDisk
        }
    }

    @Test
    fun `a copy the stitch places nowhere still draws its download`() = runTest {
        val row = row(read(5), group = listOf(Copy(5, 100)), onDisk = setOf(5L))

        row?.download?.state?.invoke() shouldBe Download.State.DOWNLOADED
    }

    @Test
    fun `a group-scoped row reads as downloaded when another source's copy is`() = runTest {
        val pooled = listOf(Copy(1, 100), Copy(11, 200))
        val row = row(read(1), group = listOf(Copy(1, 100)), pooled = pooled, onDisk = setOf(11L))

        row?.download?.state?.invoke() shouldBe Download.State.DOWNLOADED
    }

    @Test
    fun `a copy whose owner cannot be found does not count as on disk`() = runTest {
        val pooled = listOf(Copy(1, 100), Copy(11, UNKNOWN_OWNER))

        val row = row(read(1), group = listOf(Copy(1, 100)), pooled = pooled, onDisk = setOf(11L))

        row?.download?.state?.invoke() shouldBe Download.State.NOT_DOWNLOADED
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("progresses")
    fun `a chapter read on another source draws read, without its progress`(progress: ChapterProgress) = runTest {
        val pooled = listOf(Copy(1, 100, read = true), Copy(11, 200))

        row(updated(11), group = listOf(Copy(11, 200)), pooled = pooled, progress = progress)?.state shouldBe
            RecentsChapterState(read = true, bookmark = false, progress = null)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("progresses")
    fun `an unread chapter keeps its progress`(progress: ChapterProgress) = runTest {
        row(updated(11), group = listOf(Copy(11, 200)), progress = progress)?.state?.progress shouldBe progress
    }

    @Test
    fun `a chapter bookmarked on another source draws bookmarked`() = runTest {
        val pooled = listOf(Copy(1, 100, bookmark = true), Copy(11, 200))

        row(updated(11), group = listOf(Copy(11, 200)), pooled = pooled)?.state?.bookmark shouldBe true
    }

    @Test
    fun `a merged row opens the chapter under the source that owns it`() = runTest {
        row(updated(11), group = listOf(Copy(11, 200)))?.ref shouldBe ChapterRef(EntryId.Manga(200), 11L)
    }

    @Test
    fun `a named chapter whose owner cannot be found draws no row`() = runTest {
        row(updated(7), group = listOf(Copy(7, UNKNOWN_OWNER))) shouldBe null
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("labels")
    fun `the chapter is labelled the way its lane labels its own`(lane: RecentsLane, label: RecentsChapterUi) =
        runTest {
            row(lane, group = listOf(Copy(11, 200)))?.chapter shouldBe label
        }

    companion object {
        private const val UNKNOWN_OWNER = 300L

        private fun ref(chapterId: Long) = ChapterRef(EntryId.Manga(100L), chapterId)
        private fun read(chapterId: Long) = RecentsLane.Read(ref(chapterId))
        private fun updated(chapterId: Long) = RecentsLane.Updated(ref(chapterId))

        @JvmStatic
        fun progresses() = listOf(ChapterProgress.Pages(3, 10), ChapterProgress.Percent(4200))

        @JvmStatic
        fun labels() = listOf(
            Arguments.of(updated(11), RecentsChapterUi.Named("c11")),
            Arguments.of(read(11), RecentsChapterUi.Number(11.0)),
        )
    }
}
