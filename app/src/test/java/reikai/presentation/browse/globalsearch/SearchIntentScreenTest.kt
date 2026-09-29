package reikai.presentation.browse.globalsearch

import eu.kanade.tachiyomi.ui.deeplink.DeepLinkScreen
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType

/** Where an extension's search intent lands: a novel app's link handler resolves its link like a share. */
class SearchIntentScreenTest {

    private val novelPackages: suspend () -> Collection<String> = { listOf(NOVEL_APP) }

    @Test
    fun `a novel app's link opens through the shared-link tiers`() = runTest {
        searchIntentScreen(LINK, NOVEL_APP, novelPackages).shouldBeInstanceOf<DeepLinkScreen>()
    }

    @Test
    fun `a manga extension's search stays a global search`() = runTest {
        searchIntentScreen(LINK, "eu.kanade.tachiyomi.extension.en.manga", novelPackages)
            .shouldBeInstanceOf<EntryGlobalSearchScreen>()
    }

    @Test
    fun `a search naming no extension stays a global search`() = runTest {
        searchIntentScreen("query", null, novelPackages).shouldBeInstanceOf<EntryGlobalSearchScreen>()
    }

    @Test
    fun `a search naming no extension opens on every content type`() = runTest {
        searchIntentScreen("query", null, novelPackages)
            .shouldBeInstanceOf<EntryGlobalSearchScreen>().scopedContentType shouldBe ContentType.ALL
    }

    @Test
    fun `a search naming no extension covers every source`() = runTest {
        searchIntentScreen("query", null, novelPackages)
            .shouldBeInstanceOf<EntryGlobalSearchScreen>().sourceFilter shouldBe SearchSourceFilter.All
    }

    private companion object {
        const val LINK = "https://example.com/novel/1"
        const val NOVEL_APP = "eu.kanade.tachiyomi.novelextension.en.app"
    }
}
