package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.Novel
import reikai.presentation.library.novels.toLibraryItem
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

/**
 * The library's Lewd filter decides "adult" by the rule the notification check uses, so an entry from an
 * extension warned as 18+ is adult even with no adult tag, on manga and novels alike.
 */
class LibraryLewdConformanceTest {

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `an untagged entry from an adult source is left out by Lewd exclude`(type: Type) {
        libraryFilterMatches(type.row(), excludeLewd, fields(adultSource = true)) shouldBe false
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `an untagged entry from a safe source passes Lewd exclude`(type: Type) {
        libraryFilterMatches(type.row(), excludeLewd, fields(adultSource = false)) shouldBe true
    }

    enum class Type {
        MANGA {
            override fun row() = LibraryItem(
                libraryManga = LibraryManga(
                    manga = Manga.create().copy(id = 1L, genre = GENRE),
                    categories = emptyList(),
                    totalChapters = 0,
                    readCount = 0,
                    bookmarkCount = 0,
                    latestUpload = 0,
                    chapterFetchedAt = 0,
                    lastRead = 0,
                ),
                downloadCount = 0,
                unreadCount = 0,
                isLocal = false,
                badges = LibraryItem.Badges(downloadCount = 0, unreadCount = 0, isLocal = false, sourceLanguage = ""),
            )
        },
        NOVEL {
            override fun row() = LibraryNovel(
                novel = Novel.create().copy(id = 1L, genre = GENRE),
                categories = emptyList(),
                totalChapters = 0,
                readCount = 0,
                bookmarkCount = 0,
                downloadCount = 0,
                latestUpload = 0,
                chapterFetchedAt = 0,
                lastRead = 0,
            ).toLibraryItem(
                badgePrefs = LibraryBadgePrefs(false, false, false, false, false),
                sourceLanguage = "en",
                sourceIcon = SourceBadge.Generic,
                sourceName = "src",
            )
        },
        ;

        abstract fun row(): LibraryItem
    }

    private companion object {
        val GENRE = listOf("Action")

        fun fields(adultSource: Boolean) = libraryItemFilterFields(
            adultSource = { adultSource },
            lewdSourceName = { null },
            trackerIds = { emptyList() },
        )

        val excludeLewd = LibraryFilterPrefs(
            downloaded = TriState.DISABLED,
            unread = TriState.DISABLED,
            started = TriState.DISABLED,
            bookmarked = TriState.DISABLED,
            completed = TriState.DISABLED,
            intervalCustom = TriState.DISABLED,
            lewd = TriState.ENABLED_NOT,
            includedTracks = emptySet(),
            excludedTracks = emptySet(),
            categoriesActive = false,
            categoriesInclude = emptySet(),
            categoriesExclude = emptySet(),
        )
    }
}
