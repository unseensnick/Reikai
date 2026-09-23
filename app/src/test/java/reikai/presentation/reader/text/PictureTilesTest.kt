package reikai.presentation.reader.text

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** A tall picture is held as the slices around what is on screen, so the rest is never decoded. */
class PictureTilesTest {

    @Test
    @DisplayName("a picture is cut into slices, the last one short")
    fun cutIntoSlices() {
        tileCount(sourceHeight = 12_711, tileSourceHeight = 1_288) shouldBe 10
    }

    @Test
    @DisplayName("a picture with no height has no slices")
    fun noHeightNoSlices() {
        tileCount(sourceHeight = 0, tileSourceHeight = 1_288) shouldBe 0
    }

    @Test
    @DisplayName("the last slice stops at the picture's last row")
    fun lastSliceStopsAtTheEnd() {
        tileRows(index = 9, tileSourceHeight = 1_288, sourceHeight = 12_711) shouldBe 11_592..12_710
    }

    @Test
    @DisplayName("a slice past the picture covers nothing")
    fun sliceBeyondTheEnd() {
        tileRows(index = 10, tileSourceHeight = 1_288, sourceHeight = 12_711) shouldBe IntRange.EMPTY
    }

    @Test
    @DisplayName("the slices held are the ones on screen and one either side")
    fun slicesOnScreenAndOneEitherSide() {
        tilesFor(
            visibleTopPx = 4_000,
            visibleBottomPx = 6_992,
            drawnHeightPx = 19_733,
            tileCount = 10,
            ahead = 1,
        ) shouldBe
            1..4
    }

    @Test
    @DisplayName("the slices held stop at the picture's first and last")
    fun slicesStopAtTheEnds() {
        tilesFor(visibleTopPx = 0, visibleBottomPx = 2_992, drawnHeightPx = 19_733, tileCount = 10, ahead = 1) shouldBe
            0..2
    }

    @Test
    @DisplayName("a picture just scrolled past holds no slices")
    fun scrolledPastHoldsNothing() {
        tilesFor(visibleTopPx = 19_733, visibleBottomPx = 22_725, drawnHeightPx = 19_733, tileCount = 10, ahead = 1)
            .shouldBe(IntRange.EMPTY)
    }

    @Test
    @DisplayName("a picture the loader had to shrink is worth drawing from slices")
    fun shrunkenPictureIsWorthSlicing() {
        worthSlicing(sourceWidth = 800, decodedWidthPx = 200, drawnWidthPx = 1_242) shouldBe true
    }

    @Test
    @DisplayName("a picture that decoded whole has nothing sharper in its slices")
    fun wholePictureIsNotWorthSlicing() {
        worthSlicing(sourceWidth = 800, decodedWidthPx = 800, drawnWidthPx = 1_242) shouldBe false
    }

    @Test
    @DisplayName("a shrunken picture drawn no wider than it decoded is left alone")
    fun shrunkenButDrawnSmallIsNotWorthSlicing() {
        worthSlicing(sourceWidth = 800, decodedWidthPx = 200, drawnWidthPx = 200) shouldBe false
    }

    @Test
    @DisplayName("a stored strip is decoded no larger than the loader caps a fetched one")
    fun storedStripSampledToTheCap() {
        inlineSampleSize(sourceWidth = 800, sourceHeight = 12_711, columnPx = 1_242, maxSidePx = 4_096) shouldBe 4
    }

    @Test
    @DisplayName("a stored picture within the cap and the column is decoded whole")
    fun storedPictureWithinTheCap() {
        inlineSampleSize(sourceWidth = 300, sourceHeight = 600, columnPx = 1_242, maxSidePx = 4_096) shouldBe 1
    }

    @Test
    @DisplayName("a stored picture wider than the column is decoded down to it")
    fun storedPictureWiderThanTheColumn() {
        inlineSampleSize(sourceWidth = 4_000, sourceHeight = 1_000, columnPx = 1_242, maxSidePx = 4_096) shouldBe 2
    }

    @Test
    @DisplayName("a picture not yet on screen holds no slices")
    fun notOnScreenYetHoldsNothing() {
        tilesFor(visibleTopPx = -5_000, visibleBottomPx = 0, drawnHeightPx = 19_733, tileCount = 10, ahead = 1)
            .shouldBe(IntRange.EMPTY)
    }
}
