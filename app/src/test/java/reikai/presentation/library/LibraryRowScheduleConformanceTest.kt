package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.shouldBe
import mihon.domain.library.model.search.QueryNode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.Novel
import reikai.presentation.library.novels.toLibraryItem
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Instant

/**
 * A row's update schedule, its next update and its fetch interval, is read off the shared library row
 * for both types, so Mihon's `nextupdate:` and `fetchinterval:` search terms and the custom-interval
 * filter answer for novels exactly as for manga.
 */
class LibraryRowScheduleConformanceTest {

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `the fetchinterval term reads a user-set interval`(type: Type) {
        matches("fi=7", type.row()) shouldBe true
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `the nextupdate term reads the predicted update`(type: Type) {
        matches("nu>2030-06-01", type.row()) shouldBe true
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `the custom interval filter keeps an entry with a user-set interval`(type: Type) {
        libraryFilterMatches(type.row(), filterPrefs(intervalCustom = TriState.ENABLED_IS), filterFields) shouldBe true
    }

    enum class Type {
        MANGA {
            override fun row() = LibraryItem(
                libraryManga = LibraryManga(
                    manga = Manga.create().copy(id = 1L, nextUpdate = NEXT_UPDATE, fetchInterval = INTERVAL),
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
                novel = Novel.create().copy(id = 1L, nextUpdate = NEXT_UPDATE, fetchInterval = INTERVAL),
                categories = emptyList(),
                totalChapters = 0,
                readCount = 0,
                bookmarkCount = 0,
                downloadCount = 0,
                latestUpload = 0,
                chapterFetchedAt = 0,
                lastRead = 0,
            ).toLibraryItem(
                downloadBadge = false,
                unreadBadge = false,
                languageBadge = false,
                sourceLanguage = "en",
                sourceBadge = false,
                sourceIcon = null,
                sourceName = "src",
            )
        },
        ;

        abstract fun row(): LibraryItem
    }

    private companion object {
        /** A user-set interval is stored negative, as manga's is. */
        const val INTERVAL = -7
        val NEXT_UPDATE = Instant.parse("2030-06-15T12:00:00Z").toEpochMilliseconds()

        val queryFields = libraryItemQueryFields(sourceKey = { "" })
        val filterFields = libraryItemFilterFields(lewdSourceName = { null }, trackerIds = { emptyList() })

        fun matches(query: String, row: LibraryItem) = libraryQueryMatches(QueryNode.from(query), row, queryFields)

        fun filterPrefs(intervalCustom: TriState) = LibraryFilterPrefs(
            downloaded = TriState.DISABLED,
            unread = TriState.DISABLED,
            started = TriState.DISABLED,
            bookmarked = TriState.DISABLED,
            completed = TriState.DISABLED,
            intervalCustom = intervalCustom,
            lewd = TriState.DISABLED,
            includedTracks = emptySet(),
            excludedTracks = emptySet(),
            categoriesActive = false,
            categoriesInclude = emptySet(),
            categoriesExclude = emptySet(),
        )
    }
}
