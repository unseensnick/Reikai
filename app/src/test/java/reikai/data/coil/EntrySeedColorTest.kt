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

    /** Restored from disk or found by a library cover load, a colour names no cover to check against. */
    @Test
    fun `a colour kept without its cover is taken from the cover again`() = runTest {
        MangaCover.vibrantCoverColorMap[EntryId.Manga(7L).signedKey()] = RED

        EntryId.Manga(7L).seedColor(COVER, null) { BLUE } shouldBe BLUE
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
