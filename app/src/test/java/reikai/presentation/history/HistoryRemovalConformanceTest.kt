package reikai.presentation.history

import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.ui.history.HistoryViewModel
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.category.RecentsSurface
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelHistoryRepository
import reikai.domain.novel.interactor.GetCustomNovelInfo
import reikai.domain.novel.interactor.GetNovelHistory
import reikai.domain.novel.interactor.RemoveNovelHistory
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.MainDispatcherExtension
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo

/** A History row's removal is confirmed in a dialog, so leaving the tab straight after must not cut it off. */
class HistoryRemovalConformanceTest {

    private val preferences = ReikaiSourcePreferences(EmittingPreferenceStore())
    private var removed: Long? = null

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    // Builds the type's model over a repository that records which entry's history it reset, then removes it.
    private fun removeAfterClose(type: ContentType, appScope: CoroutineScope) {
        val (model, remove) = when (type) {
            ContentType.MANGA -> {
                val history = mockk<HistoryRepository> {
                    every { getHistory(any(), any(), any()) } returns flowOf(emptyList())
                    coEvery { resetHistoryByMangaId(any()) } answers { removed = firstArg() }
                }
                val model = HistoryViewModel(
                    RecentsSurface.HISTORY,
                    mockk<GetCustomMangaInfo> { every { subscribeAll() } returns flowOf(emptyList()) },
                    GetHistory(history),
                    RemoveHistory(history),
                    preferences,
                    appScope,
                )
                model to { model.removeAllFromHistory(ID) }
            }
            else -> {
                val history = mockk<NovelHistoryRepository> {
                    every { getNovelHistory(any(), any(), any()) } returns flowOf(emptyList())
                    coEvery { resetNovelHistoryByNovelId(any()) } answers { removed = firstArg() }
                }
                val model = NovelHistoryViewModel(
                    RecentsSurface.HISTORY,
                    GetNovelHistory(history),
                    mockk<GetCustomNovelInfo> { every { subscribeAll() } returns flowOf(emptyList()) },
                    RemoveNovelHistory(history),
                    preferences,
                    appScope,
                )
                model to { model.removeAllFromHistory(ID) }
            }
        }
        main.track(model).viewModelScope.cancel()
        remove()
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `removing an entry's history outlives the tab it was confirmed on`(type: ContentType) = runTest {
        removeAfterClose(type, appScope = this)
        advanceUntilIdle()

        removed shouldBe ID
    }

    private companion object {
        const val ID = 7L
    }
}
