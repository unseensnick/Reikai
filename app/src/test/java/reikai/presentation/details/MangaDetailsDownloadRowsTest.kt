package reikai.presentation.details

import eu.kanade.tachiyomi.ui.manga.MangaViewModel
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

/**
 * Whose queued downloads the manga details list follows as they change status. Novels have no such
 * rule to get wrong: their list mirrors the whole queue by chapter id (NovelDetailsDownloadStateTest).
 */
class MangaDetailsDownloadRowsTest {

    private val anchor = Manga.create().copy(id = 1L)
    private val sibling = Manga.create().copy(id = 2L)

    private fun state(merged: List<Manga> = emptyList()) = MangaViewModel.State.Success(
        manga = anchor,
        source = mockk(),
        isFromSource = false,
        chapters = emptyList(),
        mergedMangaById = merged.associateBy { it.id },
        availableScanlators = emptySet(),
        excludedScanlators = emptySet(),
    )

    @Test
    fun `a merged series follows the downloads of a sibling source it shows`() {
        state(merged = listOf(anchor, sibling)).showsChaptersOf(sibling.id) shouldBe true
    }

    @Test
    fun `an unmerged entry follows its own downloads`() {
        state().showsChaptersOf(anchor.id) shouldBe true
    }

    @Test
    fun `another series' downloads are not followed`() {
        state(merged = listOf(anchor, sibling)).showsChaptersOf(3L) shouldBe false
    }
}
