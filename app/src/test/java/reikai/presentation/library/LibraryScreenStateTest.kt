package reikai.presentation.library

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import reikai.domain.library.ContentType

/** The state each library chip shows, and how the All chip combines the two content types. */
class LibraryScreenStateTest {

    private fun state(
        isLoading: Boolean = false,
        isLibraryEmpty: Boolean = false,
        searchQuery: String? = null,
        hasActiveFilters: Boolean = false,
        showContinueButton: Boolean = false,
        overlayKey: Any? = null,
    ) = LibraryScreenState(isLoading, isLibraryEmpty, searchQuery, hasActiveFilters, showContinueButton, overlayKey)

    private val manga = state(searchQuery = "manga", overlayKey = "m")
    private val novel = state(searchQuery = "novel", overlayKey = "n")

    @Test
    fun `the Manga chip shows the manga state`() {
        LibraryScreenState.forChip(ContentType.MANGA, manga, novel) shouldBe manga
    }

    @Test
    fun `the Novels chip shows the novel state`() {
        LibraryScreenState.forChip(ContentType.NOVELS, manga, novel) shouldBe novel
    }

    @ParameterizedTest(name = "manga {0}, novels {1} -> {2}")
    @CsvSource("true,false,true", "false,true,true", "false,false,false")
    fun `All is loading while either type is`(mangaLoading: Boolean, novelLoading: Boolean, expected: Boolean) {
        LibraryScreenState.forChip(
            ContentType.ALL,
            state(isLoading = mangaLoading),
            state(isLoading = novelLoading),
        ).isLoading shouldBe expected
    }

    @ParameterizedTest(name = "manga {0}, novels {1} -> {2}")
    @CsvSource("true,true,true", "true,false,false", "false,true,false")
    fun `All is empty only when both types are`(mangaEmpty: Boolean, novelEmpty: Boolean, expected: Boolean) {
        LibraryScreenState.forChip(
            ContentType.ALL,
            state(isLibraryEmpty = mangaEmpty),
            state(isLibraryEmpty = novelEmpty),
        ).isLibraryEmpty shouldBe expected
    }

    @ParameterizedTest(name = "manga {0}, novels {1} -> {2}")
    @CsvSource("true,false,true", "false,true,true", "false,false,false")
    fun `All has active filters when either type does`(mangaActive: Boolean, novelActive: Boolean, expected: Boolean) {
        LibraryScreenState.forChip(
            ContentType.ALL,
            state(hasActiveFilters = mangaActive),
            state(hasActiveFilters = novelActive),
        ).hasActiveFilters shouldBe expected
    }

    @Test
    fun `All reads the search the engine fans out to both types`() {
        LibraryScreenState.forChip(ContentType.ALL, manga, novel).searchQuery shouldBe "manga"
    }

    @Test
    fun `All follows the shared continue-reading setting`() {
        LibraryScreenState.forChip(ContentType.ALL, state(showContinueButton = true), novel)
            .showContinueButton shouldBe true
    }

    @Test
    fun `an overlay edit on the novel side changes the All state`() {
        LibraryScreenState.forChip(ContentType.ALL, manga, novel) shouldNotBe
            LibraryScreenState.forChip(ContentType.ALL, manga, novel.copy(overlayKey = "edited"))
    }

    @Test
    fun `an overlay edit on the manga side changes the All state`() {
        LibraryScreenState.forChip(ContentType.ALL, manga, novel) shouldNotBe
            LibraryScreenState.forChip(ContentType.ALL, manga.copy(overlayKey = "edited"), novel)
    }
}
