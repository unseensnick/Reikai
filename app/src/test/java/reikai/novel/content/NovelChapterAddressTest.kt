package reikai.novel.content

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** Both readers and the download resolve a chapter's pictures and links by one rule, a browser's. */
class NovelChapterAddressTest {

    @ParameterizedTest(name = "{0} + \"{1}\" -> \"{2}\"")
    @MethodSource("addresses")
    fun `an address is made absolute the way a browser reading the chapter would`(
        baseUrl: String?,
        address: String,
        expected: String,
    ) {
        NovelChapterAddress.absolute(baseUrl, address) shouldBe expected
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("bases")
    fun `only a web address is trusted as a chapter's base`(baseUrl: String?, expected: String?) {
        NovelChapterAddress.trustedBase(baseUrl) shouldBe expected
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("webAddresses")
    fun `only an http or https address is one the browser may open`(url: String, expected: Boolean) {
        NovelChapterAddress.isWebAddress(url) shouldBe expected
    }

    companion object {
        private const val SITE = "https://site.example/novel/"

        @JvmStatic
        fun addresses() = listOf(
            Arguments.of(SITE, "img/a.jpg", "https://site.example/novel/img/a.jpg"),
            Arguments.of("https://site.example", "/a.jpg", "https://site.example/a.jpg"),
            Arguments.of(SITE, "https://cdn.example/a.jpg", "https://cdn.example/a.jpg"),
            Arguments.of(SITE, "  /a.jpg\n", "https://site.example/a.jpg"),
            // A browser reads a backslash in a web address as a slash; jsoup kept it and fetched another path.
            Arguments.of(SITE, "\\a.jpg", "https://site.example/a.jpg"),
            Arguments.of("http://site.example/", "//cdn.example/a.jpg", "http://cdn.example/a.jpg"),
            Arguments.of(null, "//cdn.example/a.jpg", "https://cdn.example/a.jpg"),
            Arguments.of("file:///sdcard/", "//cdn.example/a.jpg", "https://cdn.example/a.jpg"),
            Arguments.of(null, "HTTPS://CDN.EXAMPLE/a.jpg", "https://cdn.example/a.jpg"),
            Arguments.of("file:///sdcard/", "a.jpg", "a.jpg"),
            Arguments.of("", "/a.jpg", "/a.jpg"),
            Arguments.of(null, "a.jpg", "a.jpg"),
            Arguments.of(SITE, "", ""),
            Arguments.of(SITE, "#note-1", "#note-1"),
            Arguments.of(SITE, " data:image/png;base64,AQID\n", "data:image/png;base64,AQID"),
            Arguments.of(SITE, "mailto:author@site.example", "mailto:author@site.example"),
            Arguments.of(SITE, "reikai-anchor:3", "reikai-anchor:3"),
        )

        @JvmStatic
        fun bases() = listOf(
            Arguments.of("https://site.example/", "https://site.example/"),
            Arguments.of("http://site.example", "http://site.example/"),
            Arguments.of("file:///sdcard/", null),
            Arguments.of("content://media/1", null),
            Arguments.of("", null),
            Arguments.of(null, null),
        )

        @JvmStatic
        fun webAddresses() = listOf(
            Arguments.of("https://site.example/a", true),
            Arguments.of("http://site.example", true),
            Arguments.of("file:///data/data/app.reikai/databases/", false),
            Arguments.of("intent://evil#Intent;scheme=http;end", false),
            Arguments.of("javascript:alert(1)", false),
            Arguments.of("data:image/png;base64,AQID", false),
            Arguments.of("//cdn.example/a.jpg", false),
        )
    }
}
