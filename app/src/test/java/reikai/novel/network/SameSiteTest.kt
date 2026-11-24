package reikai.novel.network

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** A picture's site is judged by `siteHost`, the rule shared links and source icons read a site by. */
class SameSiteTest {

    // A site written with www and capitals still owns its subdomains' pictures, and only those.
    @ParameterizedTest
    @CsvSource(
        "https://cdn.novelsite.com/a.jpg, https://www.NovelSite.com, true",
        "https://othersite.com/a.jpg, https://www.novelsite.com, false",
    )
    fun `a picture is the site's own only on its host or a subdomain of it`(url: String, site: String, same: Boolean) {
        isSameSite(url, site) shouldBe same
    }
}
