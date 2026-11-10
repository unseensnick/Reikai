package reikai.domain.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
    @DisplayName("a finished chapter is counted")
    fun finishedChapterIsCounted() {
        DownloadIndexRules.isIndexed("Chapter 1_a1b2c3.html") shouldBe true
    }
}
