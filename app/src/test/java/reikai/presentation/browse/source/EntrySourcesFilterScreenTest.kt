package reikai.presentation.browse.source

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType

class EntrySourcesFilterScreenTest {

    @Test
    fun `the Novels chip shows novels`() {
        showsNovels(ContentType.NOVELS, ContentType.ALL) shouldBe true
    }

    @Test
    fun `the Novels chip shows manga while novels are hidden`() {
        showsNovels(ContentType.NOVELS, ContentType.MANGA) shouldBe false
    }

    @Test
    fun `any chip shows novels while manga is hidden`() {
        showsNovels(ContentType.MANGA, ContentType.NOVELS) shouldBe true
    }
}
