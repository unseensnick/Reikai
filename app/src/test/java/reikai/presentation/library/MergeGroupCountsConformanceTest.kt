package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.Novel
import reikai.presentation.library.novels.NovelMergeCollapse
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.Source

/**
 * A merged group's unread and download counts, pinned once over both library collapses. Group 7 holds
 * entries 1 (leading, the most chapters) and 2; group 8 holds 3 and 4 and is always stitched, so the
 * stitched maps are never empty and an absent group 7 means it has not been stitched yet.
 */
class MergeGroupCountsConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("collapses")
    fun `a group not stitched yet keeps its leading source's own unread count`(collapse: GroupCountCollapse) =
        runTest {
            collapse.group7(stitchedUnread = null, stitchedDownloads = null).unread shouldBe 5L
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collapses")
    fun `a stitched group reports its deduplicated unread count`(collapse: GroupCountCollapse) = runTest {
        collapse.group7(stitchedUnread = 6L, stitchedDownloads = null).unread shouldBe 6L
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collapses")
    fun `a group not stitched yet sums its members' downloads`(collapse: GroupCountCollapse) = runTest {
        collapse.group7(stitchedUnread = null, stitchedDownloads = null).downloads shouldBe 5L
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collapses")
    fun `a stitched group counts each chapter it holds on disk once`(collapse: GroupCountCollapse) = runTest {
        collapse.group7(stitchedUnread = null, stitchedDownloads = 2).downloads shouldBe 2L
    }

    companion object {
        @JvmStatic
        fun collapses() = listOf(MangaGroupCountCollapse(), NovelGroupCountCollapse())
    }
}

data class GroupCounts(val unread: Long, val downloads: Long)

/** One type's collapse over the fixture the test describes, returning group 7's counts. */
interface GroupCountCollapse {
    suspend fun group7(stitchedUnread: Long?, stitchedDownloads: Int?): GroupCounts
}

/** Entry id to (chapters, unread, downloads). */
private val members = mapOf(
    1L to Triple(10L, 5L, 2),
    2L to Triple(4L, 3L, 3),
    3L to Triple(1L, 0L, 0),
    4L to Triple(1L, 0L, 0),
)
private val membership = mapOf(1L to 7L, 2L to 7L, 3L to 8L, 4L to 8L)

private fun stitched(group7: Long?) = buildMap {
    put(8L, 0L)
    group7?.let { put(7L, it) }
}

class MangaGroupCountCollapse : GroupCountCollapse {

    override fun toString() = "manga"

    override suspend fun group7(stitchedUnread: Long?, stitchedDownloads: Int?): GroupCounts {
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
        val group = MangaMergeCollapse.collapse(
            items,
            membership,
            mergingEnabled = true,
            showMergeSourceIcons = false,
            resolveSource = { Source(id = it, lang = "en", name = "", supportsLatest = false, isStub = false) },
            mergedUnreadByGroup = stitched(stitchedUnread),
            mergedDownloadsByGroup = stitched(stitchedDownloads?.toLong()).mapValues { it.value.toInt() },
        ).single { 1L in it.relatedMangaIds }
        return GroupCounts(group.unreadCount, group.downloadCount.toLong())
    }
}

class NovelGroupCountCollapse : GroupCountCollapse {

    override fun toString() = "novel"

    override suspend fun group7(stitchedUnread: Long?, stitchedDownloads: Int?): GroupCounts {
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
            )
        }
        val group = NovelMergeCollapse.collapse(
            library,
            membership,
            mergingEnabled = true,
            mergedUnreadByGroup = stitched(stitchedUnread),
            mergedDownloadsByGroup = stitched(stitchedDownloads?.toLong()).mapValues { it.value.toInt() },
        ).single { 1L in it.memberIds }
        return GroupCounts(group.unreadCount, group.totalDownloadCount)
    }
}
