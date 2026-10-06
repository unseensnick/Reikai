package eu.kanade.presentation.more.settings

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.presentation.recents.EmittingPreferenceStore

class SliderPreferenceTest {

    @Test
    fun `a stepped range stops once per step`() {
        val flashDuration = Preference.PreferenceItem.SliderPreference(
            preference = EmittingPreferenceStore().getInt("millis", 100),
            title = "",
            valueRange = 100..1500 step 100,
        )

        // 15 stops: the two ends plus 13 between them.
        flashDuration.steps shouldBe 13
    }
}
