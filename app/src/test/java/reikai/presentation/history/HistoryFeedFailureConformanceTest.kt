package reikai.presentation.history

import eu.kanade.tachiyomi.ui.history.HistoryViewModel
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
import reikai.domain.novel.repository.CustomNovelInfoRepository
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.MainDispatcherExtension
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.repository.CustomMangaInfoRepository

/**
 * Both history models seed their feed null for "not loaded yet", so a query that fails has to land
 * somewhere, or the tab loads for good. Pinned once over both, each over a repository that throws.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryFeedFailureConformanceTest {

    private val preferences = ReikaiSourcePreferences(EmittingPreferenceStore())

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private fun failingFeed(type: ContentType): Flow<List<Any>?> = when (type) {
        ContentType.MANGA -> {
            val history = mockk<HistoryRepository> {
                every { getHistory(any(), any(), any()) } returns flow { error("query failed") }
            }
            val customInfo = mockk<CustomMangaInfoRepository> { every { getAllAsFlow() } returns flowOf(emptyList()) }
            main.track(
                HistoryViewModel(
                    RecentsSurface.HISTORY,
                    GetCustomMangaInfo(customInfo),
                    GetHistory(history),
                    RemoveHistory(history),
                    preferences,
                ),
            ).state.map { it.list }
        }
        ContentType.NOVELS -> {
            val history = mockk<NovelHistoryRepository> {
                every { getNovelHistory(any(), any(), any()) } returns flow { error("query failed") }
            }
            val customInfo = mockk<CustomNovelInfoRepository> { every { getAllAsFlow() } returns flowOf(emptyList()) }
            main.track(
                NovelHistoryViewModel(
                    RecentsSurface.HISTORY,
                    GetNovelHistory(history),
                    GetCustomNovelInfo(customInfo),
                    RemoveNovelHistory(history),
                    preferences,
                ),
            ).state.map { it.list }
        }
        ContentType.ALL -> error("not a content type")
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a failed history query lands on an empty feed`(type: ContentType) = runTest {
        failingFeed(type).first { it != null } shouldBe emptyList()
    }
}
