package reikai.presentation.browse.source

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType

class EntrySourcesFilterScreenTest {

    @Test
    fun `the Novels chip opens on novels`() {
        opensOnNovels(ContentType.NOVELS, ContentType.ALL) shouldBe true
    }

    @Test
    fun `the Novels chip opens on manga while novels are hidden`() {
        opensOnNovels(ContentType.NOVELS, ContentType.MANGA) shouldBe false
    }

    @Test
    fun `any chip opens on novels while manga is hidden`() {
        opensOnNovels(ContentType.MANGA, ContentType.NOVELS) shouldBe true
    }
}
