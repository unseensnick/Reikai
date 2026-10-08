package reikai.domain.source

import eu.kanade.domain.source.interactor.GetEnabledSources
import eu.kanade.domain.source.service.SourcePreferences
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.browse.source.NovelSourcesViewModel
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.source.model.Pin
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.repository.SourceRepository

/**
 * Which installed sources the Sources list shows, and pinned, from each type's own pinned, disabled and
 * language settings. One English source with id 7 on each side. Manga keeps the languages switched on
 * and novels the ones switched off, so each half turns English off its own way.
 */
class SourceListConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `an enabled source is listed unpinned`(half: Half) = runTest {
        half.rows() shouldBe mapOf(ID to false)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a pinned source is listed pinned`(half: Half) = runTest {
        half.rows(pinned = setOf(ID)) shouldBe mapOf(ID to true)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a switched-off source is not listed`(half: Half) = runTest {
        half.rows(disabled = setOf(ID)) shouldBe emptyMap()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a source in a switched-off language is not listed`(half: Half) = runTest {
        half.rows(languageOff = true) shouldBe emptyMap()
    }

    /** Each listed source id with whether its row is pinned. */
    interface Half {
        suspend fun rows(
            pinned: Set<String> = emptySet(),
            disabled: Set<String> = emptySet(),
            languageOff: Boolean = false,
        ): Map<String, Boolean>
    }

    companion object {
        private const val ID = "7"

        @JvmStatic
        fun halves(): List<Half> = listOf(
            object : Half {
                override suspend fun rows(pinned: Set<String>, disabled: Set<String>, languageOff: Boolean) =
                    SourcePreferences(EmittingPreferenceStore()).let { preferences ->
                        preferences.pinnedSources.set(pinned)
                        preferences.disabledSources.set(disabled)
                        preferences.enabledLanguages.set(if (languageOff) emptySet() else setOf("en"))
                        val source = Source(ID.toLong(), "en", "Site", supportsLatest = false, isStub = false)
                        val repository =
                            mockk<SourceRepository> { every { getSources() } returns flowOf(listOf(source)) }
                        GetEnabledSources(repository, preferences, ReikaiSourcePreferences(EmittingPreferenceStore()))
                            .subscribe().first()
                            .associate { "${it.id}" to (Pin.Pinned in it.pin) }
                    }

                override fun toString() = "manga"
            },
            object : Half {
                override suspend fun rows(pinned: Set<String>, disabled: Set<String>, languageOff: Boolean) =
                    ReikaiSourcePreferences(EmittingPreferenceStore()).let { preferences ->
                        preferences.pinnedNovelSources.set(pinned)
                        preferences.disabledNovelSources.set(disabled)
                        preferences.disabledNovelLanguages.set(if (languageOff) setOf("en") else emptySet())
                        val source = mockk<NovelSource> {
                            every { id } returns ID
                            every { lang } returns "en"
                        }
                        val manager = mockk<NovelSourceManager> {
                            every { loadedSources() } returns flowOf(listOf(source))
                        }
                        NovelSourcesViewModel(manager, preferences, ToggleNovelSource(preferences))
                            .sources.filterNotNull().first()
                            .associate { it.source.id to it.isPinned }
                    }

                override fun toString() = "novel"
            },
        )
    }
}
