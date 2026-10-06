package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** Both library row builders and both merge collapses gate their badges through [LibraryBadgePrefs]. */
class LibraryBadgePrefsTest {

    enum class Toggle(val off: LibraryBadgePrefs, val read: (LibraryItem.Badges) -> Any?, val hidden: Any?) {
        DOWNLOAD(ALL_ON.copy(download = false), { it.downloadCount }, 0),
        UNREAD(ALL_ON.copy(unread = false), { it.unreadCount }, 0L),
        LOCAL(ALL_ON.copy(local = false), { it.isLocal }, false),
        LANGUAGE(ALL_ON.copy(language = false), { it.sourceLanguage }, ""),
        SOURCE(ALL_ON.copy(source = false), { it.source }, null),
    }

    @ParameterizedTest
    @EnumSource(Toggle::class)
    fun `a badge turned off is blank on the row`(toggle: Toggle) {
        toggle.read(toggle.off.row()) shouldBe toggle.hidden
    }

    @ParameterizedTest
    @EnumSource(Toggle::class)
    fun `a badge left on shows the row's value`(toggle: Toggle) {
        (toggle.read(ALL_ON.row()) == toggle.hidden) shouldBe false
    }

    @ParameterizedTest
    @EnumSource(Toggle::class)
    fun `turning a badge off never drops the cover's source`(toggle: Toggle) {
        toggle.off.row().coverSourceId shouldBe "plugin"
    }

    private fun LibraryBadgePrefs.row() = badges(
        downloadCount = 4,
        unreadCount = 7L,
        isLocal = true,
        sourceLanguage = "ja",
        sourceBadge = SourceBadge.Generic,
        coverSourceId = "plugin",
    )
}

private val ALL_ON = LibraryBadgePrefs(download = true, unread = true, local = true, language = true, source = true)
