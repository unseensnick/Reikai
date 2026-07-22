package reikai.presentation.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.TriState

class LibraryFilterTest {

    private data class Row(
        val downloaded: Boolean = false,
        val unread: Boolean = false,
        val started: Boolean = false,
        val bookmarked: Boolean = false,
        val completed: Boolean = false,
        val intervalCustom: Boolean = false,
        val lewd: Boolean = false,
        val trackerIds: List<Long> = emptyList(),
        val categoryIds: List<Long> = emptyList(),
    )

    private val fields = LibraryFilterFields<Row>(
        isDownloaded = { it.downloaded },
        isUnread = { it.unread },
        hasStarted = { it.started },
        hasBookmarks = { it.bookmarked },
        isCompleted = { it.completed },
        matchesIntervalCustom = { it.intervalCustom },
        isLewd = { it.lewd },
        trackerIds = { it.trackerIds },
        categoryIds = { it.categoryIds },
    )

    private fun prefs(
        downloaded: TriState = TriState.DISABLED,
        unread: TriState = TriState.DISABLED,
        started: TriState = TriState.DISABLED,
        bookmarked: TriState = TriState.DISABLED,
        completed: TriState = TriState.DISABLED,
        intervalCustom: TriState = TriState.DISABLED,
        lewd: TriState = TriState.DISABLED,
        includedTracks: Set<Long> = emptySet(),
        excludedTracks: Set<Long> = emptySet(),
        categoriesActive: Boolean = false,
        categoriesInclude: Set<Long> = emptySet(),
        categoriesExclude: Set<Long> = emptySet(),
    ) = LibraryFilterPrefs(
        downloaded, unread, started, bookmarked, completed, intervalCustom, lewd,
        includedTracks, excludedTracks, categoriesActive, categoriesInclude, categoriesExclude,
    )

    private fun passes(row: Row, prefs: LibraryFilterPrefs) = libraryFilterMatches(row, prefs, fields)

    @Test
    fun `no active filter keeps every entry`() {
        passes(Row(), prefs()) shouldBe true
    }

    @Test
    fun `enabled-is keeps only matching entries`() {
        passes(Row(unread = true), prefs(unread = TriState.ENABLED_IS)) shouldBe true
        passes(Row(unread = false), prefs(unread = TriState.ENABLED_IS)) shouldBe false
    }

    @Test
    fun `enabled-not keeps only non-matching entries`() {
        passes(Row(downloaded = true), prefs(downloaded = TriState.ENABLED_NOT)) shouldBe false
        passes(Row(downloaded = false), prefs(downloaded = TriState.ENABLED_NOT)) shouldBe true
    }

    @Test
    fun `completed and lewd filter on their own fields`() {
        passes(Row(completed = true), prefs(completed = TriState.ENABLED_IS)) shouldBe true
        passes(Row(lewd = false), prefs(lewd = TriState.ENABLED_IS)) shouldBe false
    }

    @Test
    fun `interval-custom filters only when its axis is enabled`() {
        // The caller sets the axis to DISABLED when the release-period gate is off, so it never filters.
        passes(Row(intervalCustom = false), prefs(intervalCustom = TriState.DISABLED)) shouldBe true
        passes(Row(intervalCustom = false), prefs(intervalCustom = TriState.ENABLED_IS)) shouldBe false
    }

    @Test
    fun `an included tracker is required when the include set is non-empty`() {
        passes(Row(trackerIds = listOf(2L)), prefs(includedTracks = setOf(2L))) shouldBe true
        passes(Row(trackerIds = listOf(9L)), prefs(includedTracks = setOf(2L))) shouldBe false
    }

    @Test
    fun `an excluded tracker drops the entry`() {
        passes(Row(trackerIds = listOf(2L, 3L)), prefs(excludedTracks = setOf(3L))) shouldBe false
        passes(Row(trackerIds = listOf(2L)), prefs(excludedTracks = setOf(3L))) shouldBe true
    }

    @Test
    fun `no tracker sets means tracking is not filtered`() {
        passes(Row(trackerIds = emptyList()), prefs()) shouldBe true
    }

    @Test
    fun `category filter keeps included and drops excluded, only when active`() {
        passes(Row(categoryIds = listOf(1L)), prefs(categoriesActive = true, categoriesInclude = setOf(1L))) shouldBe true
        passes(Row(categoryIds = listOf(5L)), prefs(categoriesActive = true, categoriesInclude = setOf(1L))) shouldBe false
        passes(Row(categoryIds = listOf(5L)), prefs(categoriesActive = true, categoriesExclude = setOf(5L))) shouldBe false
        // Inactive: the sets are ignored.
        passes(Row(categoryIds = listOf(5L)), prefs(categoriesActive = false, categoriesInclude = setOf(1L))) shouldBe true
    }

    @Test
    fun `active axes combine with AND`() {
        val p = prefs(unread = TriState.ENABLED_IS, completed = TriState.ENABLED_IS)
        passes(Row(unread = true, completed = true), p) shouldBe true
        passes(Row(unread = true, completed = false), p) shouldBe false
        passes(Row(unread = false, completed = true), p) shouldBe false
    }
}
