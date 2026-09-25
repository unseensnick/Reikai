package reikai.presentation.download

import eu.kanade.tachiyomi.ui.more.DownloadQueueState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType

/**
 * The queue card and order rules, which both downloaders' snapshots go through. Card cases run once per
 * content type, since the kernel must answer the same for either.
 */
class DownloadQueueKernelTest {

    private fun queued(series: Long, chapter: Long, status: QueuedChapterStatus = QueuedChapterStatus.QUEUED) =
        QueuedChapter(series, chapter, status)

    private fun snapshot(
        vararg chapters: QueuedChapter,
        active: Set<Long> = emptySet(),
        completed: Map<Long, Int> = emptyMap(),
    ) = DownloadQueueSnapshot(chapters.toList(), active, completed, emptyMap())

    private fun card(type: ContentType, series: Long) = EntryDownloadCardUi(
        contentType = type,
        seriesId = series,
        sourceName = "",
        title = "",
        downloadedChapters = 0,
        totalChapters = 1,
        status = EntryDownloadCardStatus.QUEUED,
    )

    private val m1 = card(ContentType.MANGA, 1)
    private val m2 = card(ContentType.MANGA, 2)
    private val n1 = card(ContentType.NOVELS, 1)
    private val n2 = card(ContentType.NOVELS, 2)

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    @DisplayName("the total is what remains plus what the downloader finished")
    fun totalCountsRemainingAndCompleted(type: ContentType) {
        val cards = snapshot(queued(7, 1), queued(7, 2), completed = mapOf(7L to 3)).toCards(type)

        cards.single().let { it.downloadedChapters to it.totalChapters } shouldBe (3 to 5)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    @DisplayName("a series the downloader is working on reads as downloading")
    fun activeSeriesIsDownloading(type: ContentType) {
        val cards = snapshot(queued(7, 1), active = setOf(7L)).toCards(type)

        cards.single().status shouldBe EntryDownloadCardStatus.DOWNLOADING
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    @DisplayName("a series reads as failed only when every chapter left in it failed")
    fun failedOnlyWhenAllFailed(type: ContentType) {
        val cards = snapshot(
            queued(7, 1, QueuedChapterStatus.ERROR),
            queued(7, 2),
            queued(8, 3, QueuedChapterStatus.ERROR),
        ).toCards(type)

        cards.map { it.status } shouldBe listOf(EntryDownloadCardStatus.QUEUED, EntryDownloadCardStatus.ERROR)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    @DisplayName("the current chapter is the one downloading")
    fun currentChapterIsDownloadingOne(type: ContentType) {
        val cards = snapshot(
            queued(7, 1),
            queued(7, 2, QueuedChapterStatus.DOWNLOADING),
            active = setOf(7L),
        ).toCards(type)

        cards.single().currentChapterId shouldBe 2L
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    @DisplayName("between chapters the current chapter is the next one queued, skipping a failed one")
    fun currentChapterBetweenChapters(type: ContentType) {
        val cards = snapshot(
            queued(7, 1, QueuedChapterStatus.ERROR),
            queued(7, 2),
            active = setOf(7L),
        ).toCards(type)

        cards.single().currentChapterId shouldBe 2L
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    @DisplayName("a waiting series has no current chapter")
    fun waitingSeriesHasNoCurrentChapter(type: ContentType) {
        val cards = snapshot(queued(7, 1, QueuedChapterStatus.DOWNLOADING)).toCards(type)

        cards.single().currentChapterId shouldBe null
    }

    @Test
    @DisplayName("the saved order interleaves the two types")
    fun savedOrderInterleaves() {
        val arranged = arrangeCards(
            listOf(n1.cardKey, m1.cardKey),
            mapOf(ContentType.MANGA to listOf(m1), ContentType.NOVELS to listOf(n1)),
        )

        arranged shouldBe listOf(n1, m1)
    }

    @Test
    @DisplayName("within a type the downloader's own order fills the saved positions")
    fun downloaderOrderWinsWithinType() {
        val arranged = arrangeCards(
            listOf(m2.cardKey, n1.cardKey, m1.cardKey),
            mapOf(ContentType.MANGA to listOf(m1, m2), ContentType.NOVELS to listOf(n1)),
        )

        arranged shouldBe listOf(m1, n1, m2)
    }

    @Test
    @DisplayName("a series the saved order has not seen goes last")
    fun unseenSeriesGoesLast() {
        val arranged = arrangeCards(
            listOf(n1.cardKey),
            mapOf(ContentType.MANGA to listOf(m1), ContentType.NOVELS to listOf(n1)),
        )

        arranged shouldBe listOf(n1, m1)
    }

    @Test
    @DisplayName("a saved series no longer queued takes no position")
    fun goneSeriesTakesNoPosition() {
        val arranged = arrangeCards(
            listOf(n2.cardKey, m1.cardKey, n1.cardKey),
            mapOf(ContentType.MANGA to listOf(m1), ContentType.NOVELS to listOf(n1)),
        )

        arranged shouldBe listOf(m1, n1)
    }

    @Test
    @DisplayName("a series queued again after it left goes last, moving no other card")
    fun requeuedSeriesGoesLast() {
        val m3 = card(ContentType.MANGA, 3)
        val kept = prunedOrder(
            listOf(m1.cardKey, n1.cardKey),
            mapOf(ContentType.MANGA to listOf(m3), ContentType.NOVELS to listOf(n1)),
        )

        val arranged = arrangeCards(kept, mapOf(ContentType.MANGA to listOf(m3, m1), ContentType.NOVELS to listOf(n1)))

        arranged shouldBe listOf(n1, m3, m1)
    }

    @Test
    @DisplayName("moving a novel past a manga reorders only the novels")
    fun crossTypeMoveLeavesOtherTypeAlone() {
        val changes = seriesOrderChanges(listOf(m1, n1, n2), listOf(n2.cardKey, m1.cardKey, n1.cardKey))

        changes shouldBe mapOf(ContentType.NOVELS to listOf(2L, 1L))
    }

    @Test
    @DisplayName("a move that keeps every type's own order reorders no downloader")
    fun interleaveOnlyReordersNothing() {
        val changes = seriesOrderChanges(listOf(m1, n1), listOf(n1.cardKey, m1.cardKey))

        changes shouldBe emptyMap()
    }

    @Test
    @DisplayName("move to bottom puts a chapter behind the rest of its series, not the whole queue")
    fun moveToBottomStaysInSeries() {
        val queue = listOf(9L to 1L, 9L to 2L, 9L to 3L, 4L to 7L)

        queue.withChapterLastInSeries(1L, { it.second }, { it.first }) shouldBe
            listOf(9L to 2L, 9L to 3L, 9L to 1L, 4L to 7L)
    }

    @Test
    @DisplayName("a series reorder moves the named series first and keeps the rest behind, in place")
    fun seriesReorder() {
        val queue = listOf(1L to 10L, 2L to 20L, 1L to 11L, 3L to 30L, 2L to 21L)

        queue.withSeriesInOrder(listOf(3L, 1L)) { it.first } shouldBe
            listOf(3L to 30L, 1L to 10L, 1L to 11L, 2L to 20L, 2L to 21L)
    }

    @Test
    @DisplayName("a sort orders chapters within each series and keeps the series order")
    fun sortWithinSeries() {
        val chapters = listOf(9L to 3.0, 9L to 1.0, 4L to 2.0, 4L to 0.5)

        chapters.sortedWithinSeries({ it.first }, { it.second }, descending = false) shouldBe
            listOf(9L to 1.0, 9L to 3.0, 4L to 0.5, 4L to 2.0)
    }

    @Test
    @DisplayName("a descending sort reverses within each series only")
    fun sortWithinSeriesDescending() {
        val chapters = listOf(9L to 1.0, 9L to 3.0, 4L to 0.5, 4L to 2.0)

        chapters.sortedWithinSeries({ it.first }, { it.second }, descending = true) shouldBe
            listOf(9L to 3.0, 9L to 1.0, 4L to 2.0, 4L to 0.5)
    }

    @Test
    fun `an empty queue is stopped`() {
        queueState(EngineQueueStatus(pending = 0, isRunning = true)) shouldBe DownloadQueueState.Stopped
    }

    @Test
    fun `a queue whose downloader runs is downloading`() {
        queueState(
            EngineQueueStatus(pending = 2, isRunning = true),
            EngineQueueStatus(pending = 1, isRunning = false),
        ) shouldBe
            DownloadQueueState.Downloading(3)
    }

    @Test
    fun `a paused queue beside an idle empty one is paused`() {
        // Manga idle and empty, novels queued with their downloader paused: the More row used to read
        // Downloading while the queue screen offered Resume.
        queueState(
            EngineQueueStatus(pending = 0, isRunning = false),
            EngineQueueStatus(pending = 3, isRunning = false),
        ) shouldBe
            DownloadQueueState.Paused(3)
    }

    @Test
    fun `a downloader running with nothing queued does not make a paused queue downloading`() {
        queueState(
            EngineQueueStatus(pending = 0, isRunning = true),
            EngineQueueStatus(pending = 3, isRunning = false),
        ) shouldBe
            DownloadQueueState.Paused(3)
    }

    private fun queueState(vararg engines: EngineQueueStatus) = downloadQueueState(engines.toList())
}
