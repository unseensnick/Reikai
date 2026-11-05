package reikai.presentation.migrate.flow

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.ComposeTestHost

/** The app draws edge to edge, so the Copy and Migrate bar must clear the navigation bar itself. */
@RunWith(RobolectricTestRunner::class)
class MigrationCommitBarTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the Migrate button sits above the navigation bar`() {
        compose.setContent {
            ComposeTestHost {
                Box(Modifier.fillMaxSize().testTag(ROOT)) {
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        MigrationCommitBar(count = 2, onCommit = {}, windowInsets = WindowInsets(bottom = NAV_BAR))
                    }
                }
            }
        }

        val root = compose.onNodeWithTag(ROOT).getUnclippedBoundsInRoot()
        val migrate = compose.onNodeWithText("Migrate (2)").getUnclippedBoundsInRoot()

        root.bottom - migrate.bottom shouldBeGreaterThanOrEqualTo NAV_BAR
    }

    private companion object {
        const val ROOT = "root"
        val NAV_BAR = 48.dp
    }
}
