package reikai.presentation.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The cover's end badge ladder. The cover width varies with the columns setting (0..10), so what
 * survives is decided against a measured budget rather than a fixed icon cap. Order of sacrifice:
 * icons fold into "+N", then into the plain group count, then the language badge goes.
 */
class PlanEndBadgesTest {

    private val icon = 18
    private val text = 26

    private fun plan(budget: Int, sources: Int = 4, language: Boolean = true) =
        planEndBadges(
            budgetPx = budget,
            sourceCount = sources,
            maxIcons = 3,
            hasLanguage = language,
            iconWidthPx = icon,
            textBadgeWidthPx = text,
        )

    @Test
    @DisplayName("a wide cover shows the icon cap plus a +N for the rest")
    fun wide() {
        // language + 3 icons + "+1" = 26 + 54 + 26 = 106
        plan(budget = 200) shouldBe EndBadgePlan(
            showLanguage = true,
            icons = 3,
            overflow = 1,
            showGroupCount = false,
        )
    }

    @Test
    @DisplayName("no overflow badge when every source fits")
    fun allFit() {
        plan(budget = 200, sources = 3) shouldBe EndBadgePlan(
            showLanguage = true,
            icons = 3,
            overflow = 0,
            showGroupCount = false,
        )
    }

    @Test
    @DisplayName("a narrower cover drops icons before anything else")
    fun dropsIcons() {
        // 106 does not fit in 90; 2 icons + "+2" + language = 26 + 36 + 26 = 88 does
        plan(budget = 90) shouldBe EndBadgePlan(
            showLanguage = true,
            icons = 2,
            overflow = 2,
            showGroupCount = false,
        )
    }

    @Test
    @DisplayName("when no icon fits it falls back to the numeric group count")
    fun groupCount() {
        // one icon + "+3" + language = 70, too wide for 60; language + count = 52 fits
        plan(budget = 60) shouldBe EndBadgePlan(
            showLanguage = true,
            icons = 0,
            overflow = 0,
            showGroupCount = true,
        )
    }

    @Test
    @DisplayName("the language badge goes before the group count")
    fun dropsLanguage() {
        plan(budget = 30) shouldBe EndBadgePlan(
            showLanguage = false,
            icons = 0,
            overflow = 0,
            showGroupCount = true,
        )
    }

    @Test
    @DisplayName("nothing is drawn when even the count cannot fit")
    fun nothingFits() {
        plan(budget = 10) shouldBe EndBadgePlan(
            showLanguage = false,
            icons = 0,
            overflow = 0,
            showGroupCount = false,
        )
    }

    @Test
    @DisplayName("an unmerged row keeps its language badge when its source icon will not fit")
    fun unmergedKeepsLanguage() {
        // language + icon = 44, too wide for 40; the language badge alone (26) still fits, and an
        // unmerged row has no group count to fall back to
        planEndBadges(
            budgetPx = 40,
            sourceCount = 1,
            maxIcons = 1,
            hasLanguage = true,
            iconWidthPx = icon,
            textBadgeWidthPx = text,
            canShowGroupCount = false,
        ) shouldBe EndBadgePlan(
            showLanguage = true,
            icons = 0,
            overflow = 0,
            showGroupCount = false,
        )
    }

    @Test
    @DisplayName("an unmerged row shows its source icon when there is room")
    fun unmergedShowsIcon() {
        planEndBadges(
            budgetPx = 60,
            sourceCount = 1,
            maxIcons = 1,
            hasLanguage = true,
            iconWidthPx = icon,
            textBadgeWidthPx = text,
            canShowGroupCount = false,
        ) shouldBe EndBadgePlan(
            showLanguage = true,
            icons = 1,
            overflow = 0,
            showGroupCount = false,
        )
    }

    @Test
    @DisplayName("an entry with no language badge spends the whole budget on icons")
    fun noLanguage() {
        // 3 icons + "+1" = 80 fits in 90 once the language badge is not competing
        plan(budget = 90, language = false) shouldBe EndBadgePlan(
            showLanguage = false,
            icons = 3,
            overflow = 1,
            showGroupCount = false,
        )
    }
}
