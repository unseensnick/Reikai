package reikai.presentation.browse.globalsearch

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import mihon.core.navigation.DeepLinkRoute
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType

/** Where an extension's search intent lands: a novel app's link handler resolves its link like a share. */
class SearchIntentScreenTest {

    private val novelPackages: suspend () -> Collection<String> = { listOf(NOVEL_APP) }

    @Test
    fun `a novel app's link opens through the shared-link tiers`() = runTest {
        searchIntentRoute(LINK, NOVEL_APP, novelPackages) shouldBe DeepLinkRoute(LINK)
    }

    @Test
    fun `a manga extension's search stays a global search`() = runTest {
        searchIntentRoute(LINK, MANGA_EXTENSION, novelPackages) shouldBe
            EntryGlobalSearchRoute(LINK, MANGA_EXTENSION, scopedContentType = ContentType.MANGA)
    }

    @Test
    fun `a search naming no extension searches its query`() = runTest {
        searchIntentRoute("query", null, novelPackages)
            .shouldBeInstanceOf<EntryGlobalSearchRoute>().searchQuery shouldBe "query"
    }

    @Test
    fun `a search naming no extension opens on every content type`() = runTest {
        searchIntentRoute("query", null, novelPackages)
            .shouldBeInstanceOf<EntryGlobalSearchRoute>().scopedContentType shouldBe ContentType.ALL
    }

    @Test
    fun `a search naming no extension covers every source`() = runTest {
        searchIntentRoute("query", null, novelPackages)
            .shouldBeInstanceOf<EntryGlobalSearchRoute>().sourceFilter shouldBe SearchSourceFilter.All
    }

    private companion object {
        const val LINK = "https://example.com/novel/1"
        const val NOVEL_APP = "eu.kanade.tachiyomi.novelextension.en.app"
        const val MANGA_EXTENSION = "eu.kanade.tachiyomi.extension.en.manga"
    }
}
