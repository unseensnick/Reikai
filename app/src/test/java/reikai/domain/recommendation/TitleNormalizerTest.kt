package reikai.domain.recommendation

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class TitleNormalizerTest {

    private fun norm(s: String) = TitleNormalizer.normalize(s)

    @Test
    fun `strips diacritics so accented and plain titles match`() {
        norm("Café Terrace") shouldBe norm("Cafe Terrace")
    }

    @Test
    fun `folds fullwidth characters to halfwidth`() {
        norm("ＡＢＣ") shouldBe norm("ABC")
    }

    @Test
    fun `unifies different punctuation and spacing`() {
        norm("Spice ＆ Wolf") shouldBe norm("Spice & Wolf")
        norm("It’s a Test") shouldBe norm("It's a Test")
        norm("A  -  B") shouldBe norm("a b")
    }

    @Test
    fun `is case-insensitive and trims`() {
        norm("  Berserk  ") shouldBe "berserk"
    }

    @Test
    fun `keeps genuinely different titles distinct`() {
        norm("Naruto") shouldNotBe norm("Boruto")
    }

    @Test
    fun `blank input yields empty key`() {
        norm("   ") shouldBe ""
    }

    @Test
    fun `a punctuation-only title has no key`() {
        TitleNormalizer.key("!!!") shouldBe null
    }

    @Test
    fun `a title's key is its normalized form`() {
        TitleNormalizer.key("Spice & Wolf") shouldBe norm("Spice & Wolf")
    }
}
