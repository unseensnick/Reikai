package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.Novel
import reikai.presentation.library.novels.NovelMergeCollapse
import reikai.presentation.library.novels.toLibraryRow
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.Source

/**
 * What a merged library row carries besides its counts, pinned once over both library collapses.
 * Entries 1 and 2 share the first source and entry 3 is on the second; grouped, they are one row.
 * Entry 1 holds 2 downloads, entry 2 holds 3, and nothing is stitched yet.
 */
class MergedLibraryRowConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("collapses")
    fun `a merged row stands for every member`(collapse: MergedRowCollapse) = runTest {
        collapse.row().relatedMangaIds shouldContainExactlyInAnyOrder listOf(1L, 2L, 3L)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collapses")
    fun `a merged row badges each distinct source once`(collapse: MergedRowCollapse) = runTest {
        collapse.row().badges.mergedSources shouldHaveSize 2
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collapses")
    fun `a merged row draws no source icons when they are off`(collapse: MergedRowCollapse) = runTest {
        collapse.row(showSourceIcons = false).badges.mergedSources.shouldBeEmpty()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collapses")
    fun `a merged row searches each member's source once`(collapse: MergedRowCollapse) = runTest {
        collapse.row().memberSources.map { it.key } shouldContainExactlyInAnyOrder collapse.sourceKeys
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collapses")
    fun `a merged row's download badge shows the group's downloads`(collapse: MergedRowCollapse) = runTest {
        collapse.row().badges.downloadCount shouldBe 5
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collapses")
    fun `a merged row lights no download badge the user turned off`(collapse: MergedRowCollapse) = runTest {
        collapse.row(badgePrefs = allBadges.copy(download = false)).badges.downloadCount shouldBe 0
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collapses")
    fun `a merged row lights no unread badge the user turned off`(collapse: MergedRowCollapse) = runTest {
        collapse.row(badgePrefs = allBadges.copy(unread = false)).badges.unreadCount shouldBe 0L
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collapses")
    fun `an ungrouped row is not stamped as merged`(collapse: MergedRowCollapse) = runTest {
        collapse.row(grouped = false).relatedMangaIds.shouldBeEmpty()
    }

    companion object {
        @JvmStatic
        fun collapses() = listOf(MangaMergedRowCollapse(), NovelMergedRowCollapse())
    }
}

private val allBadges = LibraryBadgePrefs(download = true, unread = true, local = true, language = true, source = true)

/** One type's collapse over the fixture the test describes, returning entry 1's library row. */
interface MergedRowCollapse {
    /** The fixture's two source keys, as a member source's search key spells them. */
    val sourceKeys: List<String>

    suspend fun row(
        grouped: Boolean = true,
        showSourceIcons: Boolean = true,
        badgePrefs: LibraryBadgePrefs = allBadges,
    ): LibraryItem
}

/** Entry id to (source index, downloads); each entry lists 4 chapters with 1 read. */
private val rowMembers = mapOf(1L to (0 to 2), 2L to (0 to 3), 3L to (1 to 0))

private fun rowMembership(grouped: Boolean) = if (grouped) rowMembers.keys.associateWith { 7L } else emptyMap()

class MangaMergedRowCollapse : MergedRowCollapse {

    override fun toString() = "manga"

    private val sourceIds = listOf(100L, 200L)

    override val sourceKeys = sourceIds.map { it.toString() }

    override suspend fun row(grouped: Boolean, showSourceIcons: Boolean, badgePrefs: LibraryBadgePrefs): LibraryItem {
        val items = rowMembers.map { (id, member) ->
            val (sourceIndex, downloads) = member
            LibraryItem(
                libraryManga = LibraryManga(
                    manga = Manga.create().copy(id = id, source = sourceIds[sourceIndex]),
                    categories = emptyList(),
                    totalChapters = 4,
                    readCount = 1,
                    bookmarkCount = 0,
                    latestUpload = 0,
                    chapterFetchedAt = 0,
                    lastRead = 0,
                ),
                downloadCount = downloads,
                unreadCount = 3,
                isLocal = false,
                badges = badgePrefs.badges(
                    downloadCount = downloads,
                    unreadCount = 3,
                    isLocal = false,
                    sourceLanguage = "en",
                    sourceBadge = SourceBadge.Generic,
                ),
                sourceName = "source ${sourceIds[sourceIndex]}",
                sourceLanguage = "en",
            )
        }
        return MangaMergeCollapse.collapse(
            items,
            rowMembership(grouped),
            mergingEnabled = true,
            showMergeSourceIcons = showSourceIcons,
            resolveSource = {
                Source(id = it, lang = "en", name = "source $it", supportsLatest = false, isStub = false)
            },
            badgePrefs = badgePrefs,
        ).single { it.id == 1L }
    }
}

class NovelMergedRowCollapse : MergedRowCollapse {

    override fun toString() = "novel"

    override val sourceKeys = listOf("a", "b")

    override suspend fun row(grouped: Boolean, showSourceIcons: Boolean, badgePrefs: LibraryBadgePrefs): LibraryItem {
        val library = rowMembers.map { (id, member) ->
            val (sourceIndex, downloads) = member
            LibraryNovel(
                novel = Novel.create().copy(id = id, source = sourceKeys[sourceIndex], favoriteAt = 0L),
                categories = emptyList(),
                totalChapters = 4,
                readCount = 1,
                bookmarkCount = 0,
                downloadCount = downloads.toLong(),
                latestUpload = 0,
                chapterFetchedAt = 0,
                lastRead = 0,
            )
        }
        return NovelMergeCollapse.collapse(library, rowMembership(grouped), mergingEnabled = true)
            .single { 1L in it.memberIds }
            .toLibraryRow(
                badgePrefs = badgePrefs,
                showSourceIcons = showSourceIcons,
                querySource = { LibraryQuerySource(it, name = "source $it", language = "en", isLocal = false) },
                sourceBadge = { SourceBadge.Generic },
            )
    }
}
