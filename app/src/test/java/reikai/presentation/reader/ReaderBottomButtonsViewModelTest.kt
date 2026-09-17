package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton.Rotation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton.Scope
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton.TextSize
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton.ViewChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.novel.NovelPreferences
import reikai.presentation.recents.EmittingPreferenceStore

class ReaderBottomButtonsViewModelTest {

    private val store = EmittingPreferenceStore()
    private val readerPreferences = ReaderPreferences(store)
    private val novelPreferences = NovelPreferences(store)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(scope: Scope) = ReaderBottomButtonsViewModel(scope, readerPreferences, novelPreferences)

    private fun drawn(scope: Scope): List<ReaderBottomButton> = when (scope) {
        Scope.Manga -> ReaderBottomButton.ordered(
            readerPreferences.readerBottomButtons.get(),
            readerPreferences.readerBottomButtonOrder.get(),
            scope,
        )
        else -> ReaderBottomButton.ordered(
            novelPreferences.readerBottomButtons().get(),
            novelPreferences.readerBottomButtonOrder().get(),
            scope,
        )
    }

    @ParameterizedTest
    @EnumSource(value = Scope::class, names = ["Manga", "Novel"])
    fun `moving a button changes the order the reader draws`(scope: Scope) = runTest {
        val viewModel = viewModel(scope)
        val moved = listOf(Rotation, ViewChapters) + (ReaderBottomButton.offeredIn(scope) - Rotation - ViewChapters)

        viewModel.move(moved)

        drawn(scope).take(2) shouldBe listOf(Rotation, ViewChapters)
    }

    @ParameterizedTest
    @EnumSource(value = Scope::class, names = ["Manga", "Novel"])
    fun `turning a button off removes it from the bar`(scope: Scope) = runTest {
        val viewModel = viewModel(scope)

        viewModel.toggle(ViewChapters)

        drawn(scope).contains(ViewChapters) shouldBe false
    }

    @ParameterizedTest
    @EnumSource(value = Scope::class, names = ["Manga", "Novel"])
    fun `turning a button on adds it to the bar`(scope: Scope) = runTest {
        val viewModel = viewModel(scope)

        viewModel.toggle(ReaderBottomButton.Share)

        drawn(scope).contains(ReaderBottomButton.Share) shouldBe true
    }

    @Test
    fun `a button turned off keeps its place in the editor`() = runTest {
        val viewModel = viewModel(Scope.Novel)
        viewModel.move(listOf(TextSize) + (ReaderBottomButton.offeredIn(Scope.Novel) - TextSize))

        viewModel.toggle(TextSize)

        viewModel.rows().first() shouldBe ReaderBottomButtonsViewModel.Row(TextSize, enabled = false)
    }

    @ParameterizedTest
    @EnumSource(value = Scope::class, names = ["Manga", "Novel"])
    fun `the gear cannot be switched off`(scope: Scope) = runTest {
        viewModel(scope).toggle(ReaderBottomButton.Settings)

        drawn(scope).contains(ReaderBottomButton.Settings) shouldBe true
    }
}
