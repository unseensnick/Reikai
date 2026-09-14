package reikai.data.coil

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import tachiyomi.domain.manga.model.MangaCover

/**
 * Both details screens and both readers take an entry's tint from here, so a colour found once is
 * reused wherever that entry is opened, and never lent to the other content type's entry.
 */
class EntrySeedColorTest {

    @AfterEach
    fun clearCache() {
        MangaCover.vibrantCoverColorMap.clear()
    }

    @Test
    fun `a colour already found is returned without loading the cover again`() = runTest {
        EntryId.Novel(7L).seedColor { RED }

        EntryId.Novel(7L).seedColor { error("the cover was loaded again") } shouldBe RED
    }

    @Test
    fun `a novel never takes the colour of the manga with the same row id`() = runTest {
        EntryId.Manga(7L).seedColor { RED }

        EntryId.Novel(7L).seedColor { BLUE } shouldBe BLUE
    }

    @Test
    fun `a cover that gives no colour is tried again next time`() = runTest {
        EntryId.Manga(7L).seedColor { null }

        EntryId.Manga(7L).seedColor { BLUE } shouldBe BLUE
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
    }
}
