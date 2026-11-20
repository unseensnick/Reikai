package reikai.presentation.local

import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.data.local.ImportFile
import reikai.data.local.ImportGroup
import reikai.data.local.ImportResult
import reikai.data.local.LocalImport
import reikai.domain.library.ContentType
import reikai.presentation.ComposeTestHost
import reikai.presentation.browse.BatchCategories
import reikai.presentation.browse.MangaLibraryAdder
import reikai.util.testAppScope

/**
 * A screen handed files by another app keeps them as its arguments, which outlive process death; one restored after
 * its import ran must not offer those files again, while a new one still takes them.
 */
@RunWith(RobolectricTestRunner::class)
class LocalImportRestoreTest {

    @get:Rule
    val compose = createComposeRule()

    private val file = Uri.parse("content://files/alpha.cbz")

    // Holds an import open until completed, as a copy still running when the process dies.
    private var running: CompletableDeferred<Unit>? = null

    private val localImport = mockk<LocalImport> {
        coEvery { preview(listOf(file)) } returns (listOf(group()) to emptyList())
        coEvery { inspect(any(), any()) } answers { firstArg() }
        coEvery { import(any(), any(), any()) } coAnswers {
            running?.await()
            ImportResult(emptyList(), emptyList())
        }
    }
    private val mangaAdder = mockk<MangaLibraryAdder> {
        coEvery { resolveBatchCategories() } returns BatchCategories.Default(emptyList())
    }
    private val appScope = testAppScope()

    // A new process starts with no models, so a restore gets a fresh store as the screen's owner.
    private var owner = owner()

    private val factory = object : LocalImportViewModel.Factory {
        override fun create(initialUris: List<String>) = LocalImportViewModel(
            initialUris = initialUris,
            localImport = localImport,
            combineLocalVolumes = mockk(),
            novelAdder = mockk(),
            mangaAdder = mangaAdder,
            appScope = appScope,
        )
    }

    private val restoration = StateRestorationTester(compose)

    @After
    fun tearDown() {
        running?.complete(Unit)
        owner.viewModelStore.clear()
    }

    private fun show() = restoration.setContent {
        ComposeTestHost(assistedFactories = mapOf(LocalImportViewModel.Factory::class to { factory })) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                LocalImportScreen(listOf(file.toString()))
            }
        }
    }

    @Test
    fun `a new screen offers the files it was handed`() {
        show()

        compose.waitUntil { compose.onAllNodesWithText(TITLE).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(TITLE).fetchSemanticsNodes().size shouldBe 1
    }

    @Test
    fun `a screen restored after its import does not offer the files again`() {
        show()
        compose.waitUntil { compose.onAllNodesWithText(TITLE).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(IMPORT).performClick()
        compose.waitUntil { compose.onAllNodesWithText(TITLE).fetchSemanticsNodes().isEmpty() }

        owner.viewModelStore.clear()
        owner = owner()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil {
            listOf(CHOOSE, TITLE).any { compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }
        }

        compose.onAllNodesWithText(TITLE).fetchSemanticsNodes().size shouldBe 0
    }

    // The copy never finished, so the files the screen was handed are still to import.
    @Test
    fun `a screen restored while its import still ran offers the files again`() {
        running = CompletableDeferred()
        show()
        compose.waitUntil { compose.onAllNodesWithText(TITLE).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(IMPORT).performClick()
        compose.waitForIdle()

        owner.viewModelStore.clear()
        owner = owner()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil {
            listOf(CHOOSE, TITLE).any { compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }
        }

        compose.onAllNodesWithText(TITLE).fetchSemanticsNodes().size shouldBe 1
    }

    private fun owner() = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    private fun group() = ImportGroup(
        key = "alpha",
        type = ContentType.MANGA,
        title = TITLE,
        author = null,
        cover = null,
        files = listOf(ImportFile(file, "alpha.cbz", 1L)),
        canBeEitherType = false,
    )

    private companion object {
        const val TITLE = "Alpha"
        const val IMPORT = "Import"
        const val CHOOSE = "Choose files"
    }
}
