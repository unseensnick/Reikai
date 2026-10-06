package reikai.presentation.library

import android.content.Context
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.Novel
import reikai.presentation.library.novels.toLibraryItem
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR

/** One feed builder serves both content types, so each group mode reads the same row field for either. */
class LibraryDynamicGroupingFeedTest {

    private val context = mockk<Context>()

    @BeforeEach
    fun setUp() {
        mockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
        every { context.stringResource(MR.strings.ongoing) } returns "Ongoing"
        every { context.stringResource(MR.strings.reading) } returns "Reading"
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `tag and author groups read the row's metadata`(type: ContentType) = runTest {
        val row = row(type)

        feed(row, LibraryGroup.BY_TAG).items shouldBe listOf(DynItem(row.entryId, listOf("Drama"), "Ito", "Kai"))
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `language groups read the row's source language`(type: ContentType) = runTest {
        val row = row(type)

        feed(row, LibraryGroup.BY_LANGUAGE).languageCodes shouldBe mapOf(row.entryId to "ja")
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `status groups name the row's publishing status`(type: ContentType) = runTest {
        val row = row(type)

        feed(row, LibraryGroup.BY_STATUS).statusNames shouldBe mapOf(row.entryId to "Ongoing")
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `source groups take the adapter's source label`(type: ContentType) = runTest {
        val row = row(type)

        feed(row, LibraryGroup.BY_SOURCE).sourceMeta shouldBe mapOf(row.entryId to ("Site" to "7"))
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `tracking status groups name the group's logged-in tracker status`(type: ContentType) = runTest {
        val row = row(type)

        feed(row, LibraryGroup.BY_TRACK_STATUS).trackStatuses shouldBe mapOf(row.entryId to "Reading")
    }

    private suspend fun feed(row: LibraryItem, groupType: Int): DynamicGroupingFeed {
        val tracker = mockk<BaseTracker> { every { getStatus(1L) } returns MR.strings.reading }
        val trackerManager = mockk<TrackerManager> { every { get(5L) } returns tracker }
        return libraryDynamicGroupingFeed(
            rows = listOf(row),
            groupType = groupType,
            sourceOf = { "Site" to "7" },
            groupTracks = { listOf(track) },
            loggedInTrackerIds = setOf(5L),
            trackerManager = trackerManager,
            context = context,
        )
    }

    private val track = Track(
        id = 1L,
        mangaId = 1L,
        trackerId = 5L,
        remoteId = 0L,
        libraryId = null,
        title = "",
        lastChapterRead = 0.0,
        totalChapters = 0L,
        status = 1L,
        score = 0.0,
        remoteUrl = "",
        startDate = 0L,
        finishDate = 0L,
        private = false,
    )

    private fun row(type: ContentType): LibraryItem = when (type) {
        ContentType.NOVELS -> LibraryNovel(
            novel = Novel.create().copy(
                id = 1L,
                genre = listOf("Drama"),
                author = "Ito",
                artist = "Kai",
                status = SManga.ONGOING.toLong(),
            ),
            categories = emptyList(),
            totalChapters = 0,
            readCount = 0,
            bookmarkCount = 0,
            downloadCount = 0,
            latestUpload = 0,
            chapterFetchedAt = 0,
            lastRead = 0,
        ).toLibraryItem(LibraryBadgePrefs(false, false, false, false, false), "ja", SourceBadge.Generic, "")
        else -> LibraryItem(
            libraryManga = LibraryManga(
                manga = Manga.create().copy(
                    id = 1L,
                    genre = listOf("Drama"),
                    author = "Ito",
                    artist = "Kai",
                    status = SManga.ONGOING.toLong(),
                ),
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
            badges = LibraryItem.Badges(0, 0, false, ""),
            sourceLanguage = "ja",
        )
    }
}
