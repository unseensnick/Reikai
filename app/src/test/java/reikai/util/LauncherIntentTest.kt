package reikai.util

import android.content.Intent
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import tachiyomi.core.common.Constants

/**
 * MainActivity drops a launch that is not its task's root only when [isLauncherIntent] holds, so every
 * other entry, opened into another app's task, still reaches its screen.
 */
class LauncherIntentTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("launches")
    fun `only the launcher's own launch is dropped off the task root`(
        @Suppress("UNUSED_PARAMETER") case: String,
        action: String?,
        categories: Set<String>,
        dropped: Boolean,
    ) {
        val intent = mockk<Intent> {
            every { this@mockk.action } returns action
            every { hasCategory(any()) } answers { firstArg<String>() in categories }
        }

        intent.isLauncherIntent() shouldBe dropped
    }

    companion object {
        @JvmStatic
        fun launches() = listOf(
            Arguments.of("launcher icon", Intent.ACTION_MAIN, setOf(Intent.CATEGORY_LAUNCHER), true),
            Arguments.of("backup file opened from Files", Intent.ACTION_VIEW, setOf(Intent.CATEGORY_DEFAULT), false),
            Arguments.of("repo link", Intent.ACTION_VIEW, setOf(Intent.CATEGORY_BROWSABLE), false),
            Arguments.of("main without the launcher category", Intent.ACTION_MAIN, emptySet<String>(), false),
            Arguments.of("launcher category on a view", Intent.ACTION_VIEW, setOf(Intent.CATEGORY_LAUNCHER), false),
            Arguments.of("app shortcut", Constants.SHORTCUT_LIBRARY, emptySet<String>(), false),
            Arguments.of("explicit restart", null, emptySet<String>(), false),
        )
    }
}
