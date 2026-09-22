package eu.kanade.tachiyomi.data.track.novelupdates

import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.Novel
import reikai.domain.track.autobind.AutoBindEntry
import reikai.novel.source.NovelSource
import tachiyomi.domain.manga.model.Manga

/** Which added entries NovelUpdates binds on its own: a novel from any source reading its site. */
class NovelUpdatesAutoBindTest {

    private val tracker = NovelUpdates(TrackerManager.NOVELUPDATES)

    private fun novelFrom(site: String) =
        AutoBindEntry.Novel(Novel.create(), mockk<NovelSource> { every { this@mockk.site } returns site })

    @Test
    fun `a novel from the site's own extension or plugin is taken`() {
        tracker.accepts(novelFrom("https://www.novelupdates.com")) shouldBe true
    }

    @Test
    fun `a novel from another site is left alone`() {
        tracker.accepts(novelFrom("https://novelnice.com")) shouldBe false
    }

    @Test
    fun `a manga is never taken`() {
        tracker.accepts(AutoBindEntry.Manga(Manga.create(), mockk<Source>())) shouldBe false
    }
}
