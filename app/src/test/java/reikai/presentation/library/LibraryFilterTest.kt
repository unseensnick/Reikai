package reikai.presentation.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
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
        passes(Row(categoryIds = listOf(1L)), prefs(categoriesActive = true, categoriesInclude = setOf(1L))) shouldBe
            true
        passes(Row(categoryIds = listOf(5L)), prefs(categoriesActive = true, categoriesInclude = setOf(1L))) shouldBe
            false
        passes(Row(categoryIds = listOf(5L)), prefs(categoriesActive = true, categoriesExclude = setOf(5L))) shouldBe
            false
        // Inactive: the sets are ignored.
        passes(Row(categoryIds = listOf(5L)), prefs(categoriesActive = false, categoriesInclude = setOf(1L))) shouldBe
            true
    }

    @Test
    fun `active axes combine with AND`() {
        val p = prefs(unread = TriState.ENABLED_IS, completed = TriState.ENABLED_IS)
        passes(Row(unread = true, completed = true), p) shouldBe true
        passes(Row(unread = true, completed = false), p) shouldBe false
        passes(Row(unread = false, completed = true), p) shouldBe false
    }

    private fun settings(
        downloadedOnly: Boolean = false,
        downloaded: TriState = TriState.DISABLED,
        intervalCustom: TriState = TriState.DISABLED,
        skipsOutsideReleasePeriod: Boolean = true,
        trackers: Map<Long, TriState> = emptyMap(),
    ) = LibraryFilterSettings(
        downloadedOnly = downloadedOnly,
        downloaded = downloaded,
        unread = TriState.DISABLED,
        started = TriState.DISABLED,
        bookmarked = TriState.DISABLED,
        completed = TriState.DISABLED,
        intervalCustom = intervalCustom,
        skipsOutsideReleasePeriod = skipsOutsideReleasePeriod,
        lewd = TriState.DISABLED,
        trackers = trackers,
        categoriesEnabled = false,
        categoriesInclude = emptySet(),
        categoriesExclude = emptySet(),
    )

    // The gate is each library's own update restriction; the sheet hides the axis while it is off.
    @ParameterizedTest
    @CsvSource("true, true", "false, false")
    fun `a custom-interval filter is active only while its release-period gate is on`(gate: Boolean, active: Boolean) {
        settings(intervalCustom = TriState.ENABLED_IS, skipsOutsideReleasePeriod = gate).isActive shouldBe active
    }

    @Test
    fun `a tracker filter alone is active`() {
        settings(trackers = mapOf(2L to TriState.ENABLED_NOT, 3L to TriState.DISABLED)).isActive shouldBe true
    }

    @Test
    fun `downloaded-only mode alone is not an active filter`() {
        settings(downloadedOnly = true).isActive shouldBe false
    }

    @Test
    fun `resolving folds downloaded-only mode into the downloaded axis`() {
        settings(downloadedOnly = true).resolve().downloaded shouldBe TriState.ENABLED_IS
    }

    @Test
    fun `resolving switches the interval axis off while its gate is off`() {
        settings(intervalCustom = TriState.ENABLED_IS, skipsOutsideReleasePeriod = false)
            .resolve().intervalCustom shouldBe TriState.DISABLED
    }

    @Test
    fun `resolving splits the tracker filter into included and excluded ids`() {
        settings(trackers = mapOf(1L to TriState.ENABLED_IS, 2L to TriState.ENABLED_NOT, 3L to TriState.DISABLED))
            .resolve().let { it.includedTracks to it.excludedTracks } shouldBe (setOf(1L) to setOf(2L))
    }
}
