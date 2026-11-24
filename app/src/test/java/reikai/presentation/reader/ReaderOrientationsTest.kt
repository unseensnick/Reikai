package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class ReaderOrientationsTest {

    /** 0x38 is the mask's one value no rotation uses; Mihon's getMangaOrientation sends it to the default. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ownRotations")
    fun `an entry has a rotation of its own only when it picked one`(
        @Suppress("UNUSED_PARAMETER") name: String,
        entryFlag: Int,
        own: ReaderOrientation?,
    ) {
        ownOrientation(entryFlag) shouldBe own
    }

    @Test
    fun `an entry following the default resolves to the default`() {
        resolveOrientation(ReaderOrientation.DEFAULT.flagValue, ReaderOrientation.LANDSCAPE.flagValue) shouldBe
            ReaderOrientation.LANDSCAPE.flagValue
    }

    @Test
    fun `an entry pinned to a rotation resolves to the pin`() {
        resolveOrientation(ReaderOrientation.PORTRAIT.flagValue, ReaderOrientation.LANDSCAPE.flagValue) shouldBe
            ReaderOrientation.PORTRAIT.flagValue
    }

    @Test
    fun `the choices offer reverse portrait`() {
        readerOrientationChoices shouldContain ReaderOrientation.REVERSE_PORTRAIT
    }

    @Test
    fun `the choices leave out default`() {
        readerOrientationChoices shouldNotContain ReaderOrientation.DEFAULT
    }

    companion object {
        @JvmStatic
        fun ownRotations() = listOf(
            Arguments.of("following the default", ReaderOrientation.DEFAULT.flagValue, null),
            Arguments.of("pinned to portrait", ReaderOrientation.PORTRAIT.flagValue, ReaderOrientation.PORTRAIT),
            Arguments.of("an unknown flag", ReaderOrientation.MASK, null),
        )
    }
}
