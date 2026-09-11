package reikai.presentation.reader.text

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import reikai.presentation.reader.NovelReaderViewModel.LoadedChapter

/**
 * What the marker between two novel chapters says, which both renderers draw from, and when each
 * marker is drawn at all. The count and the rule are the ones manga's transition takes from the same
 * two chapters and the same setting.
 */
class NovelSeamTest {

    @Test
    @DisplayName("a numbering that jumps from 10 to 14 is missing three chapters")
    fun jumpIsCounted() {
        NovelSeam.between(chapter(10.0), chapter(14.0)).missingChapters shouldBe 3
    }

    @Test
    @DisplayName("consecutive chapters are missing nothing")
    fun consecutiveMissNothing() {
        NovelSeam.between(chapter(10.0), chapter(11.0)).missingChapters shouldBe 0
    }

    @Test
    @DisplayName("a chapter with no recognised number counts nothing missing")
    fun unrecognisedNumberCountsNothing() {
        NovelSeam.between(chapter(10.0), chapter(-1.0)).missingChapters shouldBe 0
    }

    @Test
    @DisplayName("a pair the order runs backwards is missing nothing rather than a negative count")
    fun backwardsPairCountsNothing() {
        NovelSeam.between(chapter(14.0), chapter(10.0)).missingChapters shouldBe 0
    }

    @Test
    @DisplayName("the finished chapter carries its own download state")
    fun finishedCarriesItsOwnDownloadState() {
        NovelSeam.between(chapter(1.0, downloaded = true), chapter(2.0)).finishedDownloaded shouldBe true
    }

    @Test
    @DisplayName("the next chapter carries its own download state")
    fun nextCarriesItsOwnDownloadState() {
        NovelSeam.between(chapter(1.0, downloaded = true), chapter(2.0)).nextDownloaded shouldBe false
    }

    @Test
    @DisplayName("the chapter that finished is named above the one below it")
    fun finishedIsNamedAboveNext() {
        NovelSeam.between(chapter(1.0), chapter(2.0)).let { it.finishedTitle to it.nextTitle } shouldBe
            ("Chapter 1.0" to "Chapter 2.0")
    }

    @Test
    @DisplayName("a seam between consecutive chapters is hidden with always-show-transition off")
    fun consecutiveSeamHiddenWithTheSettingOff() {
        NovelSeam.between(chapter(10.0), chapter(11.0)).isShown(alwaysShowTransition = false) shouldBe false
    }

    @Test
    @DisplayName("a seam between consecutive chapters shows with always-show-transition on")
    fun consecutiveSeamShownWithTheSettingOn() {
        NovelSeam.between(chapter(10.0), chapter(11.0)).isShown(alwaysShowTransition = true) shouldBe true
    }

    @Test
    @DisplayName("a seam over missing chapters shows with always-show-transition off")
    fun gapSeamShownWithTheSettingOff() {
        NovelSeam.between(chapter(10.0), chapter(14.0)).isShown(alwaysShowTransition = false) shouldBe true
    }

    @Test
    @DisplayName("the end marker shows with always-show-transition off")
    fun endMarkerShownWithTheSettingOff() {
        NovelSeam.end(chapter(10.0, isLast = true))?.isShown(alwaysShowTransition = false) shouldBe true
    }

    @Test
    @DisplayName("a chapter with one after it has no end marker")
    fun noEndMarkerWhileAChapterFollows() {
        NovelSeam.end(chapter(10.0)) shouldBe null
    }

    @Test
    @DisplayName("the end marker names the last chapter as finished and no chapter after it")
    fun endMarkerNamesTheLastChapterAndNoNext() {
        NovelSeam.end(chapter(10.0, isLast = true))?.let { it.finishedTitle to it.nextTitle } shouldBe
            ("Chapter 10.0" to null)
    }

    @Test
    @DisplayName("the end marker carries the last chapter's own download state")
    fun endMarkerCarriesItsDownloadState() {
        NovelSeam.end(chapter(10.0, downloaded = true, isLast = true))?.finishedDownloaded shouldBe true
    }

    private fun chapter(number: Double, downloaded: Boolean = false, isLast: Boolean = false) = LoadedChapter(
        chapterId = number.toLong(),
        title = "Chapter $number",
        url = "",
        html = "",
        baseUrl = null,
        progressPercent = 0,
        chapterNumber = number,
        downloaded = downloaded,
        isLast = isLast,
    )
}
