package reikai.domain.source

import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reikai.novel.source.ireader.IReaderSourceHolder
import ireader.core.source.HttpSource as IReaderHttpSource

class SourceSitesTest {

    @Test
    fun `an app source with its own home page is searched by that page alone, as Mihon`() {
        apkSource().homeUrl shouldBe HOME
    }

    @Test
    fun `an app source's cookies are cleared for its base and home URLs, as Mihon`() {
        apkSource().siteUrls shouldBe listOf(BASE, HOME)
    }

    @Test
    fun `an IReader catalogue is searched by its base URL`() {
        iReaderSource().homeUrl shouldBe IREADER
    }

    @Test
    fun `an IReader catalogue's cookies are cleared for its base URL`() {
        iReaderSource().siteUrls shouldBe listOf(IREADER)
    }

    private fun apkSource() = mockk<HttpSource> {
        every { baseUrl } returns BASE
        every { getHomeUrl() } returns HOME
    }

    private fun iReaderSource() = IReaderSourceHolder(
        mockk<IReaderHttpSource> { every { baseUrl } returns IREADER },
    )

    private companion object {
        const val BASE = "https://api.example"
        const val HOME = "https://site.example"
        const val IREADER = "https://ir.example"
    }
}
