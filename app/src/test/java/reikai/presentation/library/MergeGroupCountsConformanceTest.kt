package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.merge.MergedGroupCounts
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.Novel
import reikai.presentation.library.novels.NovelMergeCollapse
import reikai.presentation.library.novels.toLibraryItem
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.Source

/**
 * A merged group's counts, pinned once over both library collapses. Group 7 holds entries 1 (leading,
 * the most chapters, nothing read or bookmarked) and 2; group 8 holds 3 and 4 and is always stitched,
 * so the stitched maps are never empty and an absent group 7 means it has not been stitched yet.
 */
class MergeGroupCountsConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("collapses")
    fun `a group not stitched yet keeps its leading source's own unread count`(collapse: GroupCountCollapse) =
        runTest {
            collapse.group7(stitched = null, stitchedDownloads = null).unreadCount shouldBe 10L
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collapses")
    fun `a stitched group reports its deduplicated unread count`(collapse: GroupCountCollapse) = runTest {
        collapse.group7(stitched = counts(total = 6), stitchedDownloads = null).unreadCount shouldBe 6L
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collapses")
    fun `a group not stitched yet sums its members' downloads`(collapse: GroupCountCollapse) = runTest {
        collapse.group7(stitched = null, stitchedDownloads = null).downloadCount shouldBe 5
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collapses")
    fun `a stitched group counts each chapter it holds on disk once`(collapse: GroupCountCollapse) = runTest {
        collapse.group7(stitched = null, stitchedDownloads = 2).downloadCount shouldBe 2
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collapses")
    fun `a group read only on a sibling source is started`(collapse: GroupCountCollapse) = runTest {
        val row = collapse.group7(stitched = readAndBookmarkedOnSibling, stitchedDownloads = null)

        libraryFilterMatches(row, filterPrefs(started = TriState.ENABLED_NOT), filterFields) shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collapses")
    fun `a group bookmarked only on a sibling source is bookmarked`(collapse: GroupCountCollapse) = runTest {
        val row = collapse.group7(stitched = readAndBookmarkedOnSibling, stitchedDownloads = null)

        libraryFilterMatches(row, filterPrefs(bookmarked = TriState.ENABLED_IS), filterFields) shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collapses")
    fun `a stitched group searches on its read count`(collapse: GroupCountCollapse) = runTest {
        val row = collapse.group7(stitched = readAndBookmarkedOnSibling, stitchedDownloads = null)

        queryFields.readCount(row) shouldBe 1L
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collapses")
    fun `a stitched group searches on its chapter count`(collapse: GroupCountCollapse) = runTest {
        val row = collapse.group7(stitched = readAndBookmarkedOnSibling, stitchedDownloads = null)

        queryFields.totalChapters(row) shouldBe 11L
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collapses")
    fun `a stitched group sorts on its chapter count`(collapse: GroupCountCollapse) = runTest {
        val row = collapse.group7(stitched = readAndBookmarkedOnSibling, stitchedDownloads = null)

        sortFields.totalChapters(row) shouldBe 11L
    }

    companion object {
        @JvmStatic
        fun collapses() = listOf(MangaGroupCountCollapse(), NovelGroupCountCollapse())
    }
}

/** One type's collapse over the fixture the test describes, returning group 7's library row. */
interface GroupCountCollapse {
    suspend fun group7(stitched: MergedGroupCounts?, stitchedDownloads: Int?): LibraryItem
}

private fun counts(total: Long, read: Long = 0, bookmarked: Long = 0) = MergedGroupCounts(total, read, bookmarked)

/** The lead has nothing read or bookmarked; its sibling carries one read and one bookmarked chapter. */
private val readAndBookmarkedOnSibling = counts(total = 11, read = 1, bookmarked = 1)

private val filterFields = libraryItemFilterFields(lewdSourceName = { null }, trackerIds = { emptyList() })
private val queryFields = libraryItemQueryFields(sourceKey = { "" }, fetchInterval = { null }, nextUpdate = { null })
private val sortFields = libraryItemSortFields(trackerMean = { 0.0 })

private fun filterPrefs(started: TriState = TriState.DISABLED, bookmarked: TriState = TriState.DISABLED) =
    LibraryFilterPrefs(
        downloaded = TriState.DISABLED,
        unread = TriState.DISABLED,
        started = started,
        bookmarked = bookmarked,
        completed = TriState.DISABLED,
        intervalCustom = TriState.DISABLED,
        lewd = TriState.DISABLED,
        includedTracks = emptySet(),
        excludedTracks = emptySet(),
        categoriesActive = false,
        categoriesInclude = emptySet(),
        categoriesExclude = emptySet(),
    )

/** Entry id to (chapters, unread, downloads). */
private val members = mapOf(
    1L to Triple(10L, 10L, 2),
    2L to Triple(4L, 3L, 3),
    3L to Triple(1L, 0L, 0),
    4L to Triple(1L, 0L, 0),
)
private val membership = mapOf(1L to 7L, 2L to 7L, 3L to 8L, 4L to 8L)

private fun <T : Any> stitched(group7: T?, group8: T): Map<Long, T> = buildMap {
    put(8L, group8)
    group7?.let { put(7L, it) }
}

class MangaGroupCountCollapse : GroupCountCollapse {

    override fun toString() = "manga"

    override suspend fun group7(stitched: MergedGroupCounts?, stitchedDownloads: Int?): LibraryItem {
        val items = members.map { (id, counts) ->
            val (chapters, unread, downloads) = counts
            LibraryItem(
                libraryManga = LibraryManga(
                    manga = Manga.create().copy(id = id, source = 100L),
                    categories = emptyList(),
                    totalChapters = chapters,
                    readCount = chapters - unread,
                    bookmarkCount = 0,
                    latestUpload = 0,
                    chapterFetchedAt = 0,
                    lastRead = 0,
                ),
                downloadCount = downloads,
                unreadCount = unread,
                isLocal = false,
                badges = LibraryItem.Badges(
                    downloadCount = downloads,
                    unreadCount = unread,
                    isLocal = false,
                    sourceLanguage = "",
                ),
            )
        }
        return MangaMergeCollapse.collapse(
            items,
            membership,
            mergingEnabled = true,
            showMergeSourceIcons = false,
            resolveSource = { Source(id = it, lang = "en", name = "", supportsLatest = false, isStub = false) },
            mergedCountsByGroup = stitched(stitched, counts(total = 1, read = 1)),
            mergedDownloadsByGroup = stitched(stitchedDownloads, 0),
        ).single { 1L in it.relatedMangaIds }
    }
}

class NovelGroupCountCollapse : GroupCountCollapse {

    override fun toString() = "novel"

    override suspend fun group7(stitched: MergedGroupCounts?, stitchedDownloads: Int?): LibraryItem {
        val library = members.map { (id, counts) ->
            val (chapters, unread, downloads) = counts
            LibraryNovel(
                novel = Novel.create().copy(id = id, source = "src", favorite = true),
                categories = emptyList(),
                totalChapters = chapters,
                readCount = chapters - unread,
                bookmarkCount = 0,
                downloadCount = downloads.toLong(),
                latestUpload = 0,
                chapterFetchedAt = 0,
                lastRead = 0,
            )
        }
        val group = NovelMergeCollapse.collapse(
            library,
            membership,
            mergingEnabled = true,
            mergedCountsByGroup = stitched(stitched, counts(total = 1, read = 1)),
            mergedDownloadsByGroup = stitched(stitchedDownloads, 0),
        ).single { 1L in it.memberIds }
        // The row the novel library builds from a merged group: the representative's, then the group's
        // deduplicated downloads stamped on, as NovelLibraryViewModel does.
        return group.representative.toLibraryItem(
            downloadBadge = false,
            unreadBadge = false,
            languageBadge = false,
            sourceLanguage = "",
            sourceBadge = false,
            sourceIcon = null,
            sourceName = "",
        ).copy(downloadCount = group.totalDownloadCount.toInt())
    }
}
