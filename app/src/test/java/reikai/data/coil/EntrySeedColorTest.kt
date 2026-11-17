package reikai.data.coil

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import reikai.domain.entry.signedKey
import tachiyomi.domain.manga.model.MangaCover
import kotlin.time.Instant

/**
 * Both details screens and both readers take an entry's tint from here, so a colour found once is
 * reused wherever that entry is opened while its cover stays the same, and never lent to the other content
 * type's entry.
 */
class EntrySeedColorTest {

    @AfterEach
    fun clearCache() {
        MangaCover.vibrantCoverColorMap.clear()
    }

    @Test
    fun `a new cover is taken from again`() = runTest {
        EntryId.Novel(7L).seedColor(COVER, null) { RED }

        EntryId.Novel(7L).seedColor("other.jpg", null) { BLUE } shouldBe BLUE
    }

    @Test
    fun `a cover stamped as changed is taken from again`() = runTest {
        EntryId.Manga(7L).seedColor(COVER, null) { RED }

        EntryId.Manga(7L).seedColor(COVER, Instant.fromEpochMilliseconds(1)) { BLUE } shouldBe BLUE
    }

    /**
     * Restored from disk or found by a library cover load, a colour names no cover to check against. Its own id:
     * the cover each colour came from is held for the process, and another test's would answer for it.
     */
    @Test
    fun `a colour kept without its cover is taken from the cover again`() = runTest {
        MangaCover.vibrantCoverColorMap[EntryId.Manga(9L).signedKey()] = RED

        EntryId.Manga(9L).seedColor(COVER, null) { BLUE } shouldBe BLUE
    }

    /** The manga viewers build their pages before the cover is read, and an old cover's colour is wrong there. */
    @Test
    fun `a view built after a cover change gets no colour until the new cover is read`() = runTest {
        EntryId.Manga(8L).seedColor(COVER, null) { RED }

        EntryId.Manga(8L).keptSeedColor("other.jpg", null) shouldBe null
    }

    @Test
    fun `a view built once the cover is read gets its colour`() = runTest {
        EntryId.Manga(8L).seedColor(COVER, null) { RED }

        EntryId.Manga(8L).keptSeedColor(COVER, null) shouldBe RED
    }

    @Test
    fun `a colour already found is returned without loading the cover again`() = runTest {
        EntryId.Novel(7L).seedColor(COVER, null) { RED }

        EntryId.Novel(7L).seedColor(COVER, null) { error("the cover was loaded again") } shouldBe RED
    }

    @Test
    fun `a novel never takes the colour of the manga with the same row id`() = runTest {
        EntryId.Manga(7L).seedColor(COVER, null) { RED }

        EntryId.Novel(7L).seedColor(COVER, null) { BLUE } shouldBe BLUE
    }

    @Test
    fun `a cover that gives no colour is tried again next time`() = runTest {
        EntryId.Manga(7L).seedColor(COVER, null) { null }

        EntryId.Manga(7L).seedColor(COVER, null) { BLUE } shouldBe BLUE
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val COVER = "cover.jpg"
    }
}
