package reikai.presentation.details

import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.model.StubSource
import tachiyomi.source.local.LocalSource

/** How the manga page classifies the source it is viewing, which the warning and download gate read. */
class MangaSourceStateTest {

    @Test
    fun `an extension that is not installed is missing`() {
        StubSource(id = 5L, lang = "en", name = "Gone").entrySourceState() shouldBe EntrySourceState.Missing
    }

    @Test
    fun `the local source is local`() {
        mockk<Source> { every { id } returns LocalSource.ID }.entrySourceState() shouldBe EntrySourceState.Local
    }

    @Test
    fun `an installed extension is installed`() {
        mockk<Source> { every { id } returns 5L }.entrySourceState() shouldBe EntrySourceState.Installed
    }
}
