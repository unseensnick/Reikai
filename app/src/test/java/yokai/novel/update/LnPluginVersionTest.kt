package yokai.novel.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LnPluginVersionTest {

    @Test
    fun `patch bump is detected as newer`() {
        assertTrue(LnPluginVersion.compare("1.2.4", "1.2.3") > 0)
    }

    @Test
    fun `older patch ranks below newer`() {
        assertTrue(LnPluginVersion.compare("1.2.3", "1.2.4") < 0)
    }

    @Test
    fun `numeric not lexical compare`() {
        // The whole reason the helper exists: "2.0" must outrank "1.99". Lexical sort would
        // invert these because '1' < '2' but '.' < '9' at the next position.
        assertTrue(LnPluginVersion.compare("2.0", "1.99") > 0)
        assertTrue(LnPluginVersion.compare("1.10", "1.9") > 0)
    }

    @Test
    fun `missing trailing segments treated as zero`() {
        assertEquals(0, LnPluginVersion.compare("1.0", "1.0.0"))
        assertEquals(0, LnPluginVersion.compare("1", "1.0.0"))
    }

    @Test
    fun `identical versions compare equal`() {
        assertEquals(0, LnPluginVersion.compare("1.2.3", "1.2.3"))
    }

    @Test
    fun `prerelease suffix is stripped before numeric compare`() {
        // Real lnreader plugins are pure MAJOR.MINOR.PATCH; the regex strip is defensive so a
        // hypothetical "1.2.3-beta.1" still compares as 1.2.3.1 vs whatever the other side has.
        // No real plugins observed with suffixes; this protects against future format drift.
        assertTrue(LnPluginVersion.compare("1.2.4-beta", "1.2.3") > 0)
    }

    @Test
    fun `non numeric input falls back to string compare`() {
        // After stripping, "alpha" becomes empty; helper falls back to string compare on the
        // raw inputs so we still return a stable sign rather than crashing.
        assertEquals(0, LnPluginVersion.compare("alpha", "alpha"))
        assertTrue(LnPluginVersion.compare("alpha", "beta") < 0)
    }

    @Test
    fun `major bump outranks minor or patch`() {
        assertTrue(LnPluginVersion.compare("2.0.0", "1.99.99") > 0)
    }
}
