package reikai.domain.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The rules both download indexes share, the manga cache and the novel one. */
class DownloadIndexRulesTest {

    private val now = 10 * DownloadIndexRules.RENEW_INTERVAL_MS

    @Test
    @DisplayName("an index scanned within the hour is not rescanned")
    fun freshIndexIsNotStale() {
        DownloadIndexRules.isStale(lastRenew = now - DownloadIndexRules.RENEW_INTERVAL_MS, now = now) shouldBe false
    }

    @Test
    @DisplayName("an index scanned over an hour ago is rescanned")
    fun oldIndexIsStale() {
        DownloadIndexRules.isStale(lastRenew = now - DownloadIndexRules.RENEW_INTERVAL_MS - 1, now = now) shouldBe true
    }

    @Test
    @DisplayName("an index never scanned is rescanned")
    fun neverScannedIsStale() {
        DownloadIndexRules.isStale(lastRenew = 0L, now = now) shouldBe true
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
