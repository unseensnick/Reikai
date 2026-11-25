package reikai.presentation.novel

import eu.kanade.presentation.manga.DownloadAction
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.merge.GroupMarks
import reikai.domain.novel.model.NovelChapter
import kotlin.time.Instant

class NovelDownloadActionsTest {

    private fun chapter(id: Long, order: Long, read: Boolean = false, bookmark: Boolean = false) =
        NovelChapter(
            id = id, novelId = 1L, url = "u$id", name = "c$id", read = read, bookmark = bookmark,
            lastTextProgress = 0L, chapterNumber = id.toDouble(), sourceOrder = order,
            dateFetch = Instant.fromEpochMilliseconds(0L), dateUpload = null, page = "",
        )

    // The rows as the chapter list shows them, sourceOrder deliberately disagreeing with that order:
    // the caller hands over the shown list, so unread runs [12, 10, 13] here and source order would not.
    private val chapters = listOf(
        chapter(id = 11, order = 0, read = true, bookmark = true),
        chapter(id = 12, order = 1),
        chapter(id = 14, order = 3, read = true),
        chapter(id = 10, order = 2),
        chapter(id = 13, order = 4, bookmark = true),
    )

    private fun select(
        action: DownloadAction,
        readElsewhere: Set<Long>,
        bookmarkedElsewhere: Set<Long>,
        sortDescending: Boolean,
    ): List<Long> =
        selectChaptersForDownloadAction(
            chapters,
            sortDescending,
            action,
            emptySet(),
            GroupMarks(readElsewhere, bookmarkedElsewhere),
        ) { false }.map { it.id }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `the toolbar's action runs over the list as shown and the group's marks`(
        @Suppress("UNUSED_PARAMETER") case: String,
        action: DownloadAction,
        sortDescending: Boolean,
        readElsewhere: Set<Long>,
        bookmarkedElsewhere: Set<Long>,
        queued: List<Long>,
    ) {
        select(action, readElsewhere, bookmarkedElsewhere, sortDescending) shouldBe queued
    }

    companion object {
        // The kernel's own rules (hidden, excluded, next N) are DownloadCandidatesTest's.
        @JvmStatic
        fun cases() = listOf(
            Arguments.of("next 1, as shown", DownloadAction.NEXT_1_CHAPTER, false, NONE, NONE, listOf(12L)),
            // The chapter the reader reaches next.
            Arguments.of("next 1, newest first", DownloadAction.NEXT_1_CHAPTER, true, NONE, NONE, listOf(13L)),
            Arguments.of(
                "a chapter read on another source",
                DownloadAction.NEXT_5_CHAPTERS,
                false,
                setOf(12L),
                NONE,
                listOf(10L, 13L),
            ),
            Arguments.of(
                "a chapter bookmarked on another source",
                DownloadAction.BOOKMARKED_CHAPTERS,
                false,
                NONE,
                setOf(10L),
                listOf(11L, 10L, 13L),
            ),
        )

        private val NONE = emptySet<Long>()
    }
}
