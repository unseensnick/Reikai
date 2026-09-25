package reikai.domain.source

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The one cover rule a refresh follows, for manga and novels alike. */
class RefreshedCoverTest {

    private fun cover(
        stored: String? = "a",
        refreshed: String? = "a",
        manualFetch: Boolean = false,
        isLocal: Boolean = false,
        hasCustomCover: Boolean = false,
    ) = refreshedCover(stored, refreshed, manualFetch, isLocal) { hasCustomCover }

    @Test
    fun `an unchanged cover is left alone by a background refresh`() {
        cover() shouldBe CoverRefresh.KEEP
    }

    @Test
    fun `an unchanged cover is reloaded by a refresh asked for by hand`() {
        cover(manualFetch = true) shouldBe CoverRefresh.DELETE_AND_STAMP
    }

    @Test
    fun `a changed cover is reloaded`() {
        cover(refreshed = "b") shouldBe CoverRefresh.DELETE_AND_STAMP
    }

    @Test
    fun `a source with no cover never loses the stored one`() {
        cover(refreshed = null, manualFetch = true) shouldBe CoverRefresh.KEEP
    }

    @Test
    fun `a local entry is only stamped, since its file is the cover`() {
        cover(refreshed = "b", isLocal = true) shouldBe CoverRefresh.STAMP
    }

    @Test
    fun `an entry showing a custom cover drops the stale file without reloading`() {
        cover(refreshed = "b", hasCustomCover = true) shouldBe CoverRefresh.DELETE
    }
}
