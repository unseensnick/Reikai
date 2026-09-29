package reikai.domain.source

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Cases ported from LNReader's resolveSharedUrl.test.ts, plus the spellings Reikai's sources differ by. */
class SharedLinkTest {

    private fun relative(text: String, site: String) = SharedLink.parse(text)?.relativeTo(site)

    @Test
    fun `a link under a site's address gives its path below the site`() {
        relative(
            "https://testsource.example.com/fiction/21220/mother-of-learning",
            "https://testsource.example.com/",
        ) shouldBe
            "fiction/21220/mother-of-learning"
    }

    @Test
    fun `a site without a trailing slash still matches`() {
        relative("https://example.com/novel/1/", "https://example.com") shouldBe "novel/1/"
    }

    @Test
    fun `the query and fragment are dropped`() {
        relative(
            "https://testsource.example.com/fiction/21220/title?src=share#ch1",
            "https://testsource.example.com/",
        ) shouldBe
            "fiction/21220/title"
    }

    @Test
    fun `the site's own address is not an entry`() {
        relative("https://example.com", "https://example.com") shouldBe null
    }

    @Test
    fun `another site does not match`() {
        relative("https://www.someothersite.com/novel/1", "https://testsource.example.com/") shouldBe null
    }

    @Test
    fun `a host that only starts with the site's host does not match`() {
        relative("https://example.com.evil.com/x", "https://example.com") shouldBe null
    }

    @Test
    fun `a path that only starts with the site's path does not match`() {
        relative("https://example.com/novels-extra/1", "https://example.com/novels") shouldBe null
    }

    @Test
    fun `a site served under a path matches below it`() {
        relative("https://example.com/novels/1", "https://example.com/novels/") shouldBe "1"
    }

    @ParameterizedTest
    @ValueSource(strings = ["hello world", "ftp://example.com/x", "", "https://"])
    fun `text that is not an http link is not a link`(text: String) {
        SharedLink.parse(text) shouldBe null
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        relative("  https://example.com/novel/1  ", "https://example.com") shouldBe "novel/1"
    }

    @Test
    fun `www and plain http match a site stored as https without www`() {
        relative("http://www.example.com/novel/1", "https://example.com/") shouldBe "novel/1"
    }

    @Test
    fun `a site stored with www matches a link without it`() {
        relative("https://example.com/novel/1", "https://www.example.com/") shouldBe "novel/1"
    }

    @Test
    fun `the candidates are the path without and with its leading slash, then the link itself`() {
        SharedLink.parse("https://example.com/novel/1?utm=x")!!.candidates("https://example.com/") shouldBe
            listOf("novel/1", "/novel/1", "https://example.com/novel/1")
    }

    @Test
    fun `a stored spelling with the other trailing slash is looked up too`() {
        SharedLink.parse("https://example.com/novel/1")!!.storedSpellings("https://example.com/") shouldBe listOf(
            "novel/1",
            "novel/1/",
            "/novel/1",
            "/novel/1/",
            "https://example.com/novel/1",
            "https://example.com/novel/1/",
        )
    }

    private val link = SharedLink.parse("https://example.com/novel/1")!!

    @Test
    fun `an address that differs only by www, scheme and a trailing slash names the link`() {
        SharedLink.parse("http://www.example.com/novel/1/?utm=x")!!.isNamedBy("https://example.com/novel/1") shouldBe
            true
    }

    @Test
    fun `a doubled slash after the host names the same page`() {
        link.isNamedBy("https://example.com//novel/1") shouldBe true
    }

    @Test
    fun `an address on another path does not name the link`() {
        link.isNamedBy("https://example.com/book/novel/1") shouldBe false
    }

    @Test
    fun `an address the source could not give names nothing`() {
        link.isNamedBy("") shouldBe false
    }

    @Test
    fun `the candidates a source's own address rule maps back to the link are the named ones`() = runTest {
        link.named(link.candidates("https://example.com"), webUrl = { "https://example.com$it" }) shouldBe
            listOf("/novel/1")
    }

    @Test
    fun `one relative spelling that names the link is how the source spells it`() {
        SharedLink.spelling(listOf("/novel/1", "https://example.com/novel/1"), sourcePaths = emptyList()) shouldBe
            "/novel/1"
    }

    @Test
    fun `a link only the absolute address names is spelled as that address`() {
        SharedLink.spelling(listOf("https://example.com/novel/1"), sourcePaths = emptyList()) shouldBe
            "https://example.com/novel/1"
    }

    // Novel Hall's plugin: site `https://novelhall.com/`, and every path its pages give starts with `/`.
    @Test
    fun `a source whose own paths lead with a slash spells the entry with one`() {
        SharedLink.spelling(BOTH_NAMED, sourcePaths = listOf("/novel/1/c1.html", "/novel/1/c2.html")) shouldBe
            "/novel/1"
    }

    @Test
    fun `a source whose own paths carry no leading slash spells the entry without one`() {
        SharedLink.spelling(BOTH_NAMED, sourcePaths = listOf("novel/1/c1")) shouldBe "novel/1"
    }

    @Test
    fun `a source that returned no relative path of its own leaves the spelling unsettled`() {
        SharedLink.spelling(BOTH_NAMED, sourcePaths = listOf("https://example.com/novel/1/c1")) shouldBe null
    }

    @Test
    fun `a source whose own paths disagree leaves the spelling unsettled`() {
        SharedLink.spelling(BOTH_NAMED, sourcePaths = listOf("/c/1", "c/2")) shouldBe null
    }

    @Test
    fun `a search's one result whose address names the link is taken`() = runTest {
        link.soleResult(listOf("/novel/1")) { "https://example.com$it" } shouldBe "/novel/1"
    }

    @Test
    fun `a search with more than one result takes none`() = runTest {
        link.soleResult(listOf("/novel/1", "/novel/2")) { "https://example.com$it" } shouldBe null
    }

    @Test
    fun `a search's one result elsewhere on the site is not taken`() = runTest {
        link.soleResult(listOf("/novel/2")) { "https://example.com$it" } shouldBe null
    }

    @Test
    fun `the parent of an address is one level up`() {
        SharedLink.parentOf("novel/child-of-light/chapter-2") shouldBe "novel/child-of-light"
    }

    @Test
    fun `a path at the top has no parent`() {
        SharedLink.parentOf("/novel/") shouldBe null
    }

    @Test
    fun `a stored match in one source beats a guess in another`() {
        SharedLink.single(listOf("guess" to false, "stored" to true)) { it.second }?.first shouldBe "stored"
    }

    @Test
    fun `two guesses give nothing`() {
        SharedLink.single(listOf("a" to false, "b" to false)) { it.second } shouldBe null
    }

    @Test
    fun `one guess is taken`() {
        SharedLink.single(listOf("a" to false)) { it.second }?.first shouldBe "a"
    }

    private companion object {
        val BOTH_NAMED = listOf("novel/1", "/novel/1", "https://example.com/novel/1")
    }
}
