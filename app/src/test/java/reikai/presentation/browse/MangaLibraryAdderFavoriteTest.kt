package reikai.presentation.browse

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Adding or removing a manga writes its library state and nothing read before it. Adding sets the
 * default chapter flags too, so a write carrying the manga as it was read would put the old flags
 * back over them (mihon f8fff318b).
 */
class MangaLibraryAdderFavoriteTest {

    private val library = FakeMangaLibrary()

    @Test
    fun `adding leaves the chapter flags the defaults just set`() = runTest {
        library.put(7L, favorite = false)

        library.adder.favoriteFromBrowse(7L)

        library.updates.single().chapterFlags shouldBe null
    }

    @Test
    fun `adding stamps when the manga joined the library`() = runTest {
        library.put(7L, favorite = false)

        library.adder.favoriteFromBrowse(7L)

        library.rows.getValue(7L).favoriteAt shouldNotBe null
    }

    @Test
    fun `removing leaves the notes alone`() = runTest {
        val manga = library.put(7L, favorite = true)

        library.adder.removeFromLibrary(manga.copy(notes = "n"))

        library.updates.single().notes shouldBe null
    }
}
