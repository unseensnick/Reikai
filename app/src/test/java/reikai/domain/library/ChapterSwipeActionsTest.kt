package reikai.domain.library

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences.ChapterSwipeAction

/**
 * The two preference names are crossed, so binding them the obvious way puts each swipe on the wrong
 * side, silently and identically on every chapter list. Each preference holds a different action, so a
 * swapped read names the wrong one.
 */
class ChapterSwipeActionsTest {

    private val preferences = LibraryPreferences(EmittingPreferenceStore()).apply {
        swipeToEndAction.set(ChapterSwipeAction.Download)
        swipeToStartAction.set(ChapterSwipeAction.ToggleBookmark)
    }

    private val crossed =
        ChapterSwipeActions(start = ChapterSwipeAction.Download, end = ChapterSwipeAction.ToggleBookmark)

    @Test
    fun `the start side runs the preference named for the end`() {
        preferences.chapterSwipeActions() shouldBe crossed
    }

    @Test
    fun `the live pair crosses the names the same way`() = runTest {
        preferences.chapterSwipeActionsChanges().first() shouldBe crossed
    }
}
