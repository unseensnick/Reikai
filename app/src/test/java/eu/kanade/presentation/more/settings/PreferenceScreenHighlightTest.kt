package eu.kanade.presentation.more.settings

import eu.kanade.presentation.more.settings.screen.HighlightKey
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** A settings search result scrolls to the row the index names, so the index counts what the list draws. */
class PreferenceScreenHighlightTest {

    private fun group(title: String, visible: Boolean = true) = Preference.PreferenceGroup(
        title = title,
        visible = visible,
        preferenceItems = listOf(Preference.PreferenceItem.InfoPreference("row")),
    )

    @Test
    fun `a hidden group takes no rows from the scroll index`() {
        val screen = listOf(group("hidden", visible = false), group("shown"))

        screen.findHighlightedIndex(HighlightKey("shown", "row")) shouldBe 1
    }

    @Test
    fun `a visible group above still counts`() {
        val screen = listOf(group("above"), group("shown"))

        screen.findHighlightedIndex(HighlightKey("shown", "row")) shouldBe 4
    }
}
