package reikai.presentation.library.preferredsources

import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.source.local.LocalSource

class PreferredSourcesViewModelTest {

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** The local source implements the base source contract only, so a narrower type check loses it. */
    @Test
    fun `the local source can be ranked`() = runTest {
        val local = mockk<Source> {
            every { id } returns LocalSource.ID
            every { name } returns "Local source"
            every { lang } returns "other"
        }
        val model = PreferredSourcesViewModel(
            sourceManager = mockk { every { sources } returns flowOf(listOf(local)) },
            novelSourceManager = mockk(relaxed = true) { every { sources } returns flowOf(emptyList()) },
            preferences = ReikaiLibraryPreferences(EmittingPreferenceStore()),
        )

        val state = model.manga.state.filterIsInstance<PreferredSourcesState.Success>().first()
        model.viewModelScope.cancel()

        state.available.map { it.key } shouldBe listOf("${LocalSource.ID}")
    }
}
