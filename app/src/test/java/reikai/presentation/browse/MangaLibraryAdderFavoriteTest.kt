package reikai.presentation.browse

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate

/**
 * Toggling a manga's library state writes that state and nothing read before it. Adding sets the
 * default chapter flags first, so a write carrying the manga as it was read would put the old flags
 * back over them (mihon f8fff318b).
 */
class MangaLibraryAdderFavoriteTest {

    private val written = slot<MangaUpdate>()

    private val adder = MangaLibraryAdder(
        sourceManager = mockk(relaxed = true),
        coverCache = mockk(relaxed = true),
        libraryPreferences = mockk(relaxed = true),
        getCategories = mockk(relaxed = true),
        getDuplicateLibraryManga = mockk(relaxed = true),
        getManga = mockk(relaxed = true),
        setMangaCategories = mockk(relaxed = true),
        setMangaDefaultChapterFlags = mockk(relaxed = true),
        updateManga = mockk { coEvery { await(capture(written)) } returns true },
        autoBindOnAdd = mockk(relaxed = true),
        mergeManager = mockk(relaxed = true),
        transactions = mockk(relaxed = true),
        reikaiLibraryPreferences = mockk(relaxed = true),
        sourceTracker = mockk(relaxed = true),
    )

    private val manga = Manga.create().copy(id = 7, url = "/7", source = 1L, chapterFlags = 5L, notes = "n")

    @Test
    fun `adding leaves the chapter flags the defaults just set`() = runTest {
        adder.changeFavorite(manga)

        written.captured.chapterFlags shouldBe null
    }

    @Test
    fun `adding stamps when the manga joined the library`() = runTest {
        adder.changeFavorite(manga)

        written.captured.favoriteAt shouldNotBe null
    }

    @Test
    fun `removing leaves the notes alone`() = runTest {
        adder.changeFavorite(manga.copy(favoriteAt = 1L))

        written.captured.notes shouldBe null
    }
}
