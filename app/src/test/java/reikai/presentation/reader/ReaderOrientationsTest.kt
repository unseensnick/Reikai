package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReaderOrientationsTest {

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

    /** 0x38 is the mask's one value no rotation uses; Mihon's getMangaOrientation sends it to the default. */
    @Test
    fun `an entry with an unknown flag resolves to the default`() {
        resolveOrientation(ReaderOrientation.MASK, ReaderOrientation.LANDSCAPE.flagValue) shouldBe
            ReaderOrientation.LANDSCAPE.flagValue
    }

    @Test
    fun `the choices offer reverse portrait`() {
        readerOrientationChoices shouldContain ReaderOrientation.REVERSE_PORTRAIT
    }

    @Test
    fun `the choices leave out default`() {
        readerOrientationChoices shouldNotContain ReaderOrientation.DEFAULT
    }
}
