package reikai.domain.entry

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * The custom-cover key names a file on disk under `getExternalFilesDir`, so it is durable user data,
 * not a rebuildable cache: changing what these return orphans covers the user set by hand.
 */
class EntryIdKeysTest {

    // Manga keeps upstream's `mangaId.toString()` and a novel its namespaced form, which also keeps it apart
    // from a same-id manga's. Both pin the on-disk names, since a change orphans every existing cover.
    @ParameterizedTest
    @MethodSource("coverKeys")
    fun `an entry's custom-cover key stays the name its covers are stored under`(id: EntryId, key: String) {
        id.customCoverKey() shouldBe key
    }

    @Test
    fun `the signed key keeps manga and novels apart in one Long-keyed map`() {
        EntryId.Manga(12L).signedKey() shouldBe 12L
        EntryId.Novel(12L).signedKey() shouldNotBe EntryId.Manga(12L).signedKey()
    }

    private companion object {
        @JvmStatic
        fun coverKeys() = listOf(
            Arguments.of(EntryId.Manga(12L), "12"),
            Arguments.of(EntryId.Novel(12L), "novel:12"),
        )
    }
}
