package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.data.coil.GroupCover
import reikai.domain.entry.EntryCustomInfo
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelCover
import reikai.presentation.library.novels.NovelMergeCollapse
import reikai.presentation.library.novels.toLibraryRow
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("collapses")
    fun `a merged row's cover falls back to the other members in ranking order`(collapse: MergedRowCollapse) =
        runTest {
            // Preferring the second source ranks entry 3 first; 1 and 2 tie, so the lower id goes next.
            val cover = libraryCoverModel(collapse.row(preferSecondSource = true)) as GroupCover

            cover.candidates.map { it.ownerId() } shouldContainExactly listOf(3L, 1L, 2L)
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collapses")
    fun `a merged row's custom cover address is tried first`(collapse: MergedRowCollapse) = runTest {
        val cover = libraryCoverModel(collapse.row().withCustomInfo(customCover)) as GroupCover

        cover.candidates.first().url() shouldBe "custom"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collapses")
    fun `an ungrouped row draws its own cover`(collapse: MergedRowCollapse) = runTest {
        libraryCoverModel(collapse.row(grouped = false)).ownerId() shouldBe 1L
    }

    companion object {
        @JvmStatic
        fun collapses() = listOf(MangaMergedRowCollapse(), NovelMergedRowCollapse())
    }
}

private val allBadges = LibraryBadgePrefs(download = true, unread = true, local = true, language = true, source = true)

private val customCover = object : EntryCustomInfo {
    override val title: String? = null
    override val author: String? = null
    override val artist: String? = null
    override val description: String? = null
    override val genre: List<String>? = null
    override val status: Long? = null
    override val thumbnailUrl = "custom"
}

/** The entry a cover candidate is the cover of, whichever content type's model it is. */
private fun Any.ownerId(): Long = when (this) {
    is MangaCover -> mangaId
    is NovelCover -> novelId
    else -> error("Not a cover model: $this")
}

private fun Any.url(): String? = when (this) {
    is MangaCover -> url
    is NovelCover -> url
    else -> error("Not a cover model: $this")
}

/** One type's collapse over the fixture the test describes, returning the row standing for entry 1. */
interface MergedRowCollapse {
    /** The fixture's two source keys, as a member source's search key spells them. */
    val sourceKeys: List<String>

    suspend fun row(
        grouped: Boolean = true,
        showSourceIcons: Boolean = true,
        badgePrefs: LibraryBadgePrefs = allBadges,
        // Puts the second source first in the preferred-source list, so entry 3 leads.
        preferSecondSource: Boolean = false,
    ): LibraryItem
}

/**
 * Entry id to (source index, downloads); each entry lists 4 chapters with 1 read. Listed against the
 * ranking, so an order the library list happens to arrive in cannot pass for the ranking.
 */
private val rowMembers = mapOf(3L to (1 to 0), 2L to (0 to 3), 1L to (0 to 2))

private fun rowMembership(grouped: Boolean) = if (grouped) rowMembers.keys.associateWith { 7L } else emptyMap()

class MangaMergedRowCollapse : MergedRowCollapse {

    override fun toString() = "manga"

    private val sourceIds = listOf(100L, 200L)

    override val sourceKeys = sourceIds.map { it.toString() }

    override suspend fun row(
        grouped: Boolean,
        showSourceIcons: Boolean,
        badgePrefs: LibraryBadgePrefs,
        preferSecondSource: Boolean,
    ): LibraryItem {
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
            preferredSourceIds = if (preferSecondSource) listOf(sourceIds[1]) else emptyList(),
        ).single { 1L in it.memberIds() }
    }
}

class NovelMergedRowCollapse : MergedRowCollapse {

    override fun toString() = "novel"

    override val sourceKeys = listOf("a", "b")

    override suspend fun row(
        grouped: Boolean,
        showSourceIcons: Boolean,
        badgePrefs: LibraryBadgePrefs,
        preferSecondSource: Boolean,
    ): LibraryItem {
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
        val preferred = if (preferSecondSource) listOf(sourceKeys[1]) else emptyList()
        return NovelMergeCollapse.collapse(
            library,
            rowMembership(grouped),
            mergingEnabled = true,
            preferredSourceIds = preferred,
        )
            .single { 1L in it.memberIds }
            .toLibraryRow(
                badgePrefs = badgePrefs,
                showSourceIcons = showSourceIcons,
                querySource = { LibraryQuerySource(it, name = "source $it", language = "en", isLocal = false) },
                sourceBadge = { SourceBadge.Generic },
            )
    }
}
