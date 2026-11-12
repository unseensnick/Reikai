package reikai.domain.download

import eu.kanade.tachiyomi.data.download.Downloader
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import reikai.storage.IN_FLIGHT_SUFFIX
import kotlin.time.Duration.Companion.hours

/** The rules both download indexes share, the manga cache and the novel one. */
class DownloadIndexRulesTest {

    private val now = 100.hours.inWholeMilliseconds

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 6, 12, 24])
    @DisplayName("an index scanned within the interval is not rescanned")
    fun freshIndexIsNotStale(hours: Int) {
        val lastRenew = now - hours.hours.inWholeMilliseconds
        DownloadIndexRules.isStale(lastRenew, now, intervalHours = hours) shouldBe false
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 6, 12, 24])
    @DisplayName("an index scanned longer ago than the interval is rescanned")
    fun oldIndexIsStale(hours: Int) {
        val lastRenew = now - hours.hours.inWholeMilliseconds - 1
        DownloadIndexRules.isStale(lastRenew, now, intervalHours = hours) shouldBe true
    }

    @ParameterizedTest
    @ValueSource(ints = [DownloadIndexRules.MANUAL, 1, 24])
    @DisplayName("an index never scanned, or thrown away by Reindex downloads, is rescanned")
    fun neverScannedIsStale(hours: Int) {
        DownloadIndexRules.isStale(lastRenew = 0L, now = now, intervalHours = hours) shouldBe true
    }

    @Test
    @DisplayName("a manual interval never rescans an index on its own, however old")
    fun manualIsNeverStale() {
        DownloadIndexRules.isStale(lastRenew = 1L, now = now, intervalHours = DownloadIndexRules.MANUAL) shouldBe false
    }

    @Test
    @DisplayName("a chapter still being written is not counted")
    fun halfWrittenChapterIsSkipped() {
        DownloadIndexRules.isIndexed("Chapter 1_a1b2c3.html_tmp") shouldBe false
    }

    @Test
    @DisplayName("a chapter folder a move set aside is not counted as a second download")
    fun parkedChapterIsSkipped() {
        DownloadIndexRules.isIndexed(".moved-Chapter 1_a1b2c3") shouldBe false
    }

    /**
     * The manga downloader writes upstream's suffix and the novel writer and the merge write the gateway's, while
     * [DownloadIndexRules.isIndexed] reads one: the two must stay one string, or a half-written file reads as done.
     */
    @Test
    @DisplayName("every writer marks a file in flight with the suffix the indexes skip")
    fun inFlightSuffixesAgree() {
        IN_FLIGHT_SUFFIX shouldBe Downloader.TMP_DIR_SUFFIX
    }

    @Test
    @DisplayName("a finished chapter is counted")
    fun finishedChapterIsCounted() {
        DownloadIndexRules.isIndexed("Chapter 1_a1b2c3.html") shouldBe true
    }
}
