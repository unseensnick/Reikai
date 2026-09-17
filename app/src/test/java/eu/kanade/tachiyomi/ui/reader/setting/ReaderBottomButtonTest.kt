package eu.kanade.tachiyomi.ui.reader.setting

import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton.Rotation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton.Scope
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton.Settings
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton.Share
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton.TextSize
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton.Theme
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton.ViewChapters
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.novel.NovelPreferences
import reikai.presentation.recents.EmittingPreferenceStore

class ReaderBottomButtonTest {

    private val selected = setOf(ViewChapters, Rotation, TextSize, Theme).map { it.value }.toSet()

    @Test
    fun `with no stored order the buttons keep the order the bar has always drawn`() {
        ReaderBottomButton.ordered(selected, order = emptyList(), Scope.Novel) shouldBe
            listOf(ViewChapters, Rotation, Theme, TextSize, Settings)
    }

    @Test
    fun `a stored order is the order the buttons are drawn in`() {
        val order = listOf(TextSize, ViewChapters, Theme, Rotation).map { it.value }

        ReaderBottomButton.ordered(selected, order, Scope.Novel) shouldBe
            listOf(TextSize, ViewChapters, Theme, Rotation, Settings)
    }

    @Test
    fun `a selected button missing from the stored order is drawn after the ordered ones`() {
        val order = listOf(TextSize, ViewChapters).map { it.value }

        ReaderBottomButton.ordered(selected, order, Scope.Novel) shouldBe
            listOf(TextSize, ViewChapters, Rotation, Theme, Settings)
    }

    @Test
    fun `a stored code no button has is ignored`() {
        val order = listOf("gone", TextSize.value)

        ReaderBottomButton.ordered(setOf(TextSize.value), order, Scope.Novel) shouldBe listOf(TextSize, Settings)
    }

    @Test
    fun `an ordered button that is not selected is not drawn`() {
        val order = listOf(Share, ViewChapters).map { it.value }

        ReaderBottomButton.ordered(setOf(ViewChapters.value), order, Scope.Novel) shouldBe
            listOf(ViewChapters, Settings)
    }

    @Test
    fun `the editor lists every offered button, the stored order first`() {
        val order = listOf(TextSize, Share).map { it.value }

        ReaderBottomButton.arranged(order, Scope.Manga) shouldBe
            listOf(Share) + (ReaderBottomButton.offeredIn(Scope.Manga) - Share)
    }

    @Test
    fun `a selected button the reader does not offer is not drawn`() {
        val manga = setOf(ViewChapters, TextSize).map { it.value }.toSet()

        ReaderBottomButton.ordered(manga, order = emptyList(), Scope.Manga) shouldBe listOf(ViewChapters, Settings)
    }

    @Test
    fun `the gear is drawn though nothing selects it`() {
        ReaderBottomButton.ordered(emptySet(), order = emptyList(), Scope.Novel) shouldBe listOf(Settings)
    }

    @Test
    fun `the gear is drawn where the stored order puts it`() {
        val order = listOf(Settings, ViewChapters).map { it.value }

        ReaderBottomButton.ordered(setOf(ViewChapters.value), order, Scope.Manga) shouldBe
            listOf(Settings, ViewChapters)
    }

    @ParameterizedTest
    @EnumSource(value = Scope::class, names = ["Manga", "Novel"])
    fun `a reader's bar preferences are the ones for that reader`(scope: Scope) {
        val store = EmittingPreferenceStore()

        ReaderBottomButton.BarPreferences.of(scope, ReaderPreferences(store), NovelPreferences(store)).scope shouldBe
            scope
    }
}
