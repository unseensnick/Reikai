package reikai.presentation.browse.extension

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.kanade.presentation.browse.PrivateExtensionUninstallDialog
import eu.kanade.presentation.browse.UninstallPromptDialog
import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.matchers.shouldBe
import mihon.domain.extension.model.ContentWarning
import mihon.domain.extension.model.ExtensionStore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import reikai.presentation.ComposeTestHost
import reikai.presentation.browse.components.NovelSourceIcon

/** The uninstall prompt a private extension and an LN plugin both raise, as each caller draws it. */
@RunWith(ParameterizedRobolectricTestRunner::class)
class UninstallPromptDialogTest(private val caller: Caller) {

    @get:Rule
    val compose = createComposeRule()

    private var uninstalled = false

    private fun show() = compose.setContent {
        ComposeTestHost { caller.prompt({ uninstalled = true }, {}) }
    }

    @Test
    fun `the prompt names what it uninstalls`() {
        show()

        compose.onNodeWithText(NAME).assertExists()
    }

    @Test
    fun `Uninstall uninstalls`() {
        show()

        compose.onNodeWithText("Uninstall").performClick()

        uninstalled shouldBe true
    }

    @Test
    fun `Cancel leaves it installed`() {
        show()

        compose.onNodeWithText("Cancel").performClick()

        uninstalled shouldBe false
    }

    class Caller(
        private val label: String,
        val prompt: @Composable (onConfirm: () -> Unit, onDismiss: () -> Unit) -> Unit,
    ) {
        override fun toString() = label
    }

    companion object {
        const val NAME = "Example"

        private val extension = Extension.Available(
            name = NAME,
            pkgName = "app.example",
            versionName = "1",
            versionCode = 1,
            libVersion = 1.4,
            lang = "en",
            contentWarning = ContentWarning.SAFE,
            kind = Extension.Kind.MANGA,
            sources = emptyList(),
            apkUrl = "",
            iconUrl = "",
            store = ExtensionStore("", "", "", "", ExtensionStore.Contact("", null), false, null),
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun callers(): List<Array<Any>> = listOf(
            arrayOf(
                Caller("extension") { onConfirm, onDismiss ->
                    PrivateExtensionUninstallDialog(extension, onConfirm, onDismiss)
                },
            ),
            arrayOf(
                Caller("plugin") { onConfirm, onDismiss ->
                    UninstallPromptDialog(NAME, { NovelSourceIcon(null) }, onConfirm, onDismiss)
                },
            ),
        )
    }
}
