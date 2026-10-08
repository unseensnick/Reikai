package reikai.novel.network

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** A picture's site is judged by `siteHost`, the rule shared links and source icons read a site by. */
class SameSiteTest {

    @Test
    fun `a site written with www and capitals still owns its subdomains' pictures`() {
        listOf(
            isSameSite("https://cdn.novelsite.com/a.jpg", "https://www.NovelSite.com"),
            isSameSite("https://othersite.com/a.jpg", "https://www.novelsite.com"),
        ) shouldBe listOf(true, false)
    }
}
