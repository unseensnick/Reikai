package reikai.presentation.novel.details

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.Novel

/** The details screen's filter icon and lazy page fetch both read [NovelDetailsState.Loaded.chapterFilters]. */
class NovelDetailsChapterFiltersTest {

    @Test
    fun `a list filtered only by the Downloaded only switch counts as filtered`() {
        val novel = Novel.create()

        NovelDetailsState.Loaded(novel, novel, emptyList(), downloadedFilterLocked = true)
            .chapterFilters.isActive shouldBe true
    }
}
