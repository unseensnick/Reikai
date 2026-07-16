package reikai.presentation.novel.browse

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reikai.novel.host.NovelItem

/**
 * Covers the novel-specific selection keying. Because a browse result is a bare [NovelItem] with no id,
 * a selection must key on (sourceId, path); the same path under two sources must stay distinct. The
 * add-to-library routing mirrors the manga [reikai.presentation.browse.BulkFavoriteScreenModel] and runs
 * on the screen-model scope, so it is exercised on-device, not here.
 */
class NovelBulkFavoriteScreenModelTest {

    private fun model() = NovelBulkFavoriteScreenModel(
        libraryAdder = mockk(relaxed = true),
        getNovelCategories = mockk(relaxed = true),
        novelPreferences = mockk(relaxed = true),
    )

    private fun item(path: String) = NovelItem(name = "Novel $path", path = path, cover = null)

    @Test
    fun `selecting the same source and path twice toggles it off`() {
        val model = model()

        model.toggleSelection("s1", item("a"))
        model.state.value.selection.map { it.key } shouldBe listOf("s1" to "a")

        model.toggleSelection("s1", item("a"))
        model.state.value.selection.map { it.key } shouldBe emptyList()
    }

    @Test
    fun `the same path under different sources are distinct selections`() {
        val model = model()

        model.toggleSelection("s1", item("a"))
        model.toggleSelection("s2", item("a"))

        model.state.value.selection.map { it.key } shouldBe listOf("s1" to "a", "s2" to "a")
    }

    @Test
    fun `reverseSelection keeps only the previously-unselected items`() {
        val model = model()
        model.toggleSelection("s1", item("a"))

        model.reverseSelection(listOf(SelectedNovel("s1", item("a")), SelectedNovel("s1", item("b"))))

        model.state.value.selection.map { it.key } shouldBe listOf("s1" to "b")
    }

    @Test
    fun `exiting selection mode clears the selection`() {
        val model = model()
        model.toggleSelection("s1", item("a"))

        model.toggleSelectionMode(false)

        model.state.value.selectionMode shouldBe false
        model.state.value.selection.map { it.key } shouldBe emptyList()
    }
}
