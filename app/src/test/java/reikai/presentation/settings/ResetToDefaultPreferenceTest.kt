package reikai.presentation.settings

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import reikai.presentation.recents.EmittingPreferenceStore

class ResetToDefaultPreferenceTest {

    private val address = EmittingPreferenceStore().getString("address", DEFAULT)

    @ParameterizedTest(name = "{0}, setting shown {1} -> reset row shown {2}")
    @CsvSource(
        "$DEFAULT, true, false",
        "https://moved.example, true, true",
        "https://moved.example, false, false",
        "'', true, true",
    )
    fun `the reset row shows only while a shown setting is off its default`(
        current: String,
        settingShown: Boolean,
        expected: Boolean,
    ) {
        resetToDefaultPreference(address, current, TITLE, settingShown).enabled shouldBe expected
    }

    @Test
    fun `tapping the reset row puts the default back`() {
        address.set("https://moved.example")

        resetToDefaultPreference(address, address.get(), TITLE).onClick!!()

        address.get() shouldBe DEFAULT
    }

    private companion object {
        const val DEFAULT = "https://built-in.example"
        const val TITLE = "Reset"
    }
}
