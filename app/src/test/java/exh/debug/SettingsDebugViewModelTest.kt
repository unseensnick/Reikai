package exh.debug

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import org.junit.jupiter.api.Test

/** The debug menu lists each public debug function by a readable name, and nothing private. */
class SettingsDebugViewModelTest {

    private val labels = SettingsDebugViewModel.menuFunctions().map { it.label }

    @Test
    fun `a public function is listed by its spaced name`() {
        labels shouldContain "Force upgrade migration"
    }

    @Test
    fun `a private helper is not listed`() {
        labels shouldNotContain "Run migrations"
    }
}
