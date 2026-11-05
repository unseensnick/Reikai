package reikai.presentation.browse.catalogue

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.ComposeTestHost
import reikai.presentation.browse.EntryGestures

/**
 * A local catalogue of either type: its empty listing offers adding files and the guide rather than a
 * retry, and coming back from the import re-runs the cached listing so the files just added show.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogueLocalSourceTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `an empty local listing offers the local-source guide beside Import`() {
        showEmpty(LocalSourceActions(onImport = {}, onGuide = {}))

        compose.onNodeWithText("Local source guide").assertIsDisplayed()
    }

    @Test
    fun `an empty local listing offers no retry`() {
        showEmpty(LocalSourceActions(onImport = {}, onGuide = {}))

        compose.onAllNodesWithText("Retry").fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun `an armed refresh runs once when the screen next resumes`() {
        val owner = TestOwner()
        var refreshes = 0
        lateinit var arm: () -> Unit
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                arm = rememberRefreshOnReturn { refreshes++ }
            }
        }
        compose.runOnIdle { arm() }

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }

        compose.runOnIdle { refreshes shouldBe 1 }
    }

    private fun showEmpty(localSource: LocalSourceActions?) = compose.setContent {
        ComposeTestHost {
            val notLoading = LoadState.NotLoading(endOfPaginationReached = true)
            val rows = flowOf(PagingData.empty<EntryBrowseRow>(LoadStates(notLoading, notLoading, notLoading)))
                .collectAsLazyPagingItems()
            EntryBrowseCatalogue(
                rows = rows,
                rowStyle = EntryBrowseRowStyle.Gallery,
                selectedKeys = emptySet(),
                snackbarHostState = SnackbarHostState(),
                contentPadding = PaddingValues(),
                onWebViewClick = {},
                onHelpClick = {},
                gestures = EntryGestures({}, {}, { false }),
                localSource = localSource,
            )
        }
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.STARTED }
        override val lifecycle: Lifecycle get() = registry
    }
}
