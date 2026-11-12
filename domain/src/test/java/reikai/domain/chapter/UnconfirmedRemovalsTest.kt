package reikai.domain.chapter

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Which removed local chapters a sync may take. A folder whose contents change can be read under another shape
 * with its name unchanged: a folder of pages that gains an archive becomes a volume, and a volume that loses its
 * archives becomes a chapter. Its listing then shows the folder was read, so the old address is really gone.
 */
class UnconfirmedRemovalsTest {

    // A manga's Extras is the volume folder itself, so only an address ending in a slash is a container.
    private val containerOf = { url: String -> url.takeIf { it.endsWith('/') } }

    // The folder's entry is still in its parent, as it is after a reshape.
    private val stillInParent: suspend (String) -> Boolean = { true }

    @Test
    fun `a chapter folder now read as a volume is gone`() = runTest {
        unconfirmedRemovals(
            removed = listOf("Series/Vol 1"),
            listed = listOf("Series/Vol 1/", "Series/Vol 1/ch1.cbz"),
            containerOf = containerOf,
            present = stillInParent,
        ) shouldBe emptyList()
    }

    @Test
    fun `a volume's Extras now read as a chapter is gone`() = runTest {
        unconfirmedRemovals(
            removed = listOf("Series/Vol 1/"),
            listed = listOf("Series/Vol 1"),
            containerOf = containerOf,
            present = stillInParent,
        ) shouldBe emptyList()
    }

    @Test
    fun `a chapter the listing left out while its file is there is kept`() = runTest {
        unconfirmedRemovals(
            removed = listOf("Series/Vol 1"),
            listed = listOf("Series/Vol 2"),
            containerOf = containerOf,
            present = stillInParent,
        ) shouldBe listOf("Series/Vol 1")
    }
}
