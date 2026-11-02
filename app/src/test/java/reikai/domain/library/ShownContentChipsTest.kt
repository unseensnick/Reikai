package reikai.domain.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.recents.EmittingPreferenceStore

class ShownContentChipsTest {

    private val store = EmittingPreferenceStore()
    private val source = ReikaiSourcePreferences(store)
    private val library = ReikaiLibraryPreferences(store)

    @Test
    fun `every persisted chip reads the shown type`() {
        source.shownContent.set(ContentType.NOVELS)

        listOf(
            library.libraryContentType,
            source.browseContentType,
            source.updatesContentType,
            source.historyContentType,
            source.recentsContentType,
            source.statsContentType,
        ).map { it.get() }.distinct() shouldBe listOf(ContentType.NOVELS)
    }

    @Test
    fun `both holders read one Show setting`() {
        source.shownContent.set(ContentType.MANGA)

        library.shownContent.get() shouldBe ContentType.MANGA
    }
}
