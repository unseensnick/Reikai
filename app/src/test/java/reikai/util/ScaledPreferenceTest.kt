package reikai.util

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.presentation.reader.NovelTextRanges
import reikai.presentation.reader.ReaderRanges
import reikai.presentation.recents.EmittingPreferenceStore

class ScaledPreferenceTest {

    @ParameterizedTest
    @MethodSource("tenths")
    fun `a tenths value reads back as itself`(tenths: Int) {
        val scaled = EmittingPreferenceStore().getFloat("f", 1f).scaled(ReaderRanges.TENTHS)
        scaled.set(tenths)
        scaled.get() shouldBe tenths
    }

    @ParameterizedTest
    @MethodSource("percents")
    fun `a percent value reads back as itself`(percent: Int) {
        val scaled = EmittingPreferenceStore().getFloat("f", 0.75f).scaled(ReaderRanges.PERCENT)
        scaled.set(percent)
        scaled.get() shouldBe percent
    }

    @ParameterizedTest
    @MethodSource("tenths")
    fun `a tenths value is stored as the float the sliders wrote before`(tenths: Int) {
        val base = EmittingPreferenceStore().getFloat("f", 1f)
        base.scaled(ReaderRanges.TENTHS).set(tenths)
        base.get() shouldBe tenths / 10f
    }

    @Test
    fun `a stored float just under a tenth rounds to it`() {
        val base = EmittingPreferenceStore().getFloat("f", 1f)
        base.set(1.29999f)
        base.scaled(ReaderRanges.TENTHS).get() shouldBe 13
    }

    @Test
    fun `the default scales`() {
        EmittingPreferenceStore().getFloat("f", 0.75f).scaled(ReaderRanges.PERCENT).defaultValue() shouldBe 75
    }

    @Test
    fun `changes emit the scaled value`() = runTest {
        val base = EmittingPreferenceStore().getFloat("f", 1f)
        base.set(1.5f)
        base.scaled(ReaderRanges.TENTHS).changes().first() shouldBe 15
    }

    companion object {
        @JvmStatic
        fun tenths() = (
            NovelTextRanges.lineHeightTenths + NovelTextRanges.paragraphIndentTenths +
                NovelTextRanges.paragraphSpacingTenths + NovelTextRanges.readAloudRateTenths +
                NovelTextRanges.readAloudPitchTenths + ReaderRanges.autoScrollSpeedTenths
            ).distinct()

        @JvmStatic
        fun percents() = ReaderRanges.volumeKeyScrollPercent.toList()
    }
}
