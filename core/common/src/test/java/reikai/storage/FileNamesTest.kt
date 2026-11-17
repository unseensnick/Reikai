package reikai.storage

import eu.kanade.tachiyomi.util.storage.DiskUtil
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FileNamesTest {

    /** "é" is two bytes, so a suffix counted in characters would leave the name one byte over the limit. */
    @Test
    fun `a long name keeps its whole suffix within the limit in bytes`() {
        val suffix = " - é.ttf"

        validFileName("a".repeat(400), suffix) shouldBe "a".repeat(DiskUtil.MAX_FILE_NAME_BYTES - 9) + suffix
    }
}
