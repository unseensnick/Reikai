package reikai.domain.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibrarySort

/**
 * Every sort and display mode, Reikai's own included, reads back from its stored string as itself, so a
 * dropped string arm cannot silently reset a user's choice to the default.
 */
class LibraryModeSerializationTest {

    @ParameterizedTest
    @MethodSource("sorts")
    fun `a stored sort reads back as itself`(sort: LibrarySort) {
        LibrarySort.deserialize(sort.serialize()) shouldBe sort
    }

    @ParameterizedTest
    @MethodSource("displayModes")
    fun `a stored display mode reads back as itself`(mode: LibraryDisplayMode) {
        LibraryDisplayMode.deserialize(mode.serialize()) shouldBe mode
    }

    companion object {
        @JvmStatic
        fun sorts() = LibrarySort.types.flatMap { type -> LibrarySort.directions.map { LibrarySort(type, it) } }

        @JvmStatic
        fun displayModes() = LibraryDisplayMode.values.toList()
    }
}
