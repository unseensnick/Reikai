package reikai.domain.entry

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * The custom-cover key names a file on disk under `getExternalFilesDir`, so it is durable user data,
 * not a rebuildable cache: changing what these return orphans covers the user set by hand.
 */
class EntryIdKeysTest {

    @Test
    fun `a manga's custom-cover key stays its plain id`() {
        // Upstream names the file by `mangaId.toString()`. Diverging here would orphan every existing
        // manga cover, so this pins the on-disk contract rather than the implementation.
        EntryId.Manga(12L).customCoverKey() shouldBe "12"
    }

    @Test
    fun `a novel's custom-cover key is namespaced away from a same-id manga`() {
        EntryId.Novel(12L).customCoverKey() shouldNotBe EntryId.Manga(12L).customCoverKey()
    }

    @Test
    fun `the colour-cache key keeps manga and novels apart in one Long-keyed map`() {
        EntryId.Manga(12L).vibrantColorKey() shouldBe 12L
        EntryId.Novel(12L).vibrantColorKey() shouldNotBe EntryId.Manga(12L).vibrantColorKey()
    }
}
