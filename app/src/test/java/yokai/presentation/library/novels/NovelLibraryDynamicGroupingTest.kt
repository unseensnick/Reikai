package yokai.presentation.library.novels

import eu.kanade.tachiyomi.data.database.models.LibraryNovel
import eu.kanade.tachiyomi.data.database.models.NovelCategory
import eu.kanade.tachiyomi.ui.library.LibraryGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import yokai.domain.novel.models.Novel

class NovelLibraryDynamicGroupingTest {

    @Test
    fun `empty library returns empty`() {
        val result = NovelLibraryDynamicGrouping.build(
            libraryNovel = emptyList(),
            groupType = LibraryGroup.BY_SOURCE,
            librarySortingMode = 0,
            librarySortingAscending = true,
            collapsedDynamicCategories = emptySet(),
            collapsedDynamicAtBottom = false,
            unknownLabel = "Unknown",
            notTrackedLabel = "Not tracked",
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `BY_DEFAULT returns empty (caller routes through NovelLibrarySectioner)`() {
        val library = listOf(libraryNovel(1, "X"))
        val result = NovelLibraryDynamicGrouping.build(
            libraryNovel = library,
            groupType = LibraryGroup.BY_DEFAULT,
            librarySortingMode = 0,
            librarySortingAscending = true,
            collapsedDynamicCategories = emptySet(),
            collapsedDynamicAtBottom = false,
            unknownLabel = "Unknown",
            notTrackedLabel = "Not tracked",
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `BY_LANGUAGE is not supported for novels and returns empty`() {
        val library = listOf(libraryNovel(1, "X"))
        val result = NovelLibraryDynamicGrouping.build(
            libraryNovel = library,
            groupType = LibraryGroup.BY_LANGUAGE,
            librarySortingMode = 0,
            librarySortingAscending = true,
            collapsedDynamicCategories = emptySet(),
            collapsedDynamicAtBottom = false,
            unknownLabel = "Unknown",
            notTrackedLabel = "Not tracked",
        )
        assertTrue(result.isEmpty())
    }

    // --- BY_SOURCE ---------------------------------------------------------------------------

    @Test
    fun `BY_SOURCE groups novels by source name and stores String sourceId on category`() {
        val library = listOf(
            libraryNovel(1, "Novel A"),
            libraryNovel(2, "Novel B"),
            libraryNovel(3, "Novel C"),
        )
        val sourceMeta = mapOf(
            1L to ("Royal Road" to "royalroad"),
            2L to ("NovelBin" to "novelbin"),
            3L to ("Royal Road" to "royalroad"),
        )
        val result = NovelLibraryDynamicGrouping.build(
            libraryNovel = library,
            groupType = LibraryGroup.BY_SOURCE,
            librarySortingMode = 0,
            librarySortingAscending = true,
            collapsedDynamicCategories = emptySet(),
            collapsedDynamicAtBottom = false,
            unknownLabel = "Unknown",
            notTrackedLabel = "Not tracked",
            sourceMeta = sourceMeta,
        )
        val byName = result.mapKeys { it.key.name }
        assertEquals(setOf("Royal Road", "NovelBin"), byName.keys)
        assertEquals(setOf(1L, 3L), byName["Royal Road"]!!.mapNotNull { it.libraryNovel.novel.id }.toSet())
        assertEquals(listOf(2L), byName["NovelBin"]!!.map { it.libraryNovel.novel.id })
        val royalRoad = result.keys.first { it.name == "Royal Road" }
        assertEquals("royalroad", royalRoad.sourceId)
    }

    @Test
    fun `BY_SOURCE buckets missing-source novels under unknownLabel`() {
        val library = listOf(libraryNovel(1, "Novel A"))
        val result = NovelLibraryDynamicGrouping.build(
            libraryNovel = library,
            groupType = LibraryGroup.BY_SOURCE,
            librarySortingMode = 0,
            librarySortingAscending = true,
            collapsedDynamicCategories = emptySet(),
            collapsedDynamicAtBottom = false,
            unknownLabel = "Unknown",
            notTrackedLabel = "Not tracked",
            sourceMeta = emptyMap(),
        )
        val names = result.keys.map { it.name }
        assertEquals(listOf("Unknown"), names)
    }

    // --- BY_TAG ------------------------------------------------------------------------------

    @Test
    fun `BY_TAG iterates the genres list and places novels in every matching tag`() {
        val library = listOf(
            libraryNovel(1, "Novel A", genres = listOf("Action", "Romance")),
            libraryNovel(2, "Novel B", genres = listOf("Romance", "Slice of Life")),
        )
        val result = NovelLibraryDynamicGrouping.build(
            libraryNovel = library,
            groupType = LibraryGroup.BY_TAG,
            librarySortingMode = 0,
            librarySortingAscending = true,
            collapsedDynamicCategories = emptySet(),
            collapsedDynamicAtBottom = false,
            unknownLabel = "Unknown",
            notTrackedLabel = "Not tracked",
        )
        val byName = result.mapKeys { it.key.name }
        assertEquals(setOf("Action", "Romance", "Slice Of Life"), byName.keys)
        assertEquals(listOf(1L), byName["Action"]!!.map { it.libraryNovel.novel.id })
        assertEquals(setOf(1L, 2L), byName["Romance"]!!.mapNotNull { it.libraryNovel.novel.id }.toSet())
        assertEquals(listOf(2L), byName["Slice Of Life"]!!.map { it.libraryNovel.novel.id })
    }

    @Test
    fun `BY_TAG with null genres buckets under unknownLabel`() {
        val library = listOf(libraryNovel(1, "Novel A", genres = null))
        val result = NovelLibraryDynamicGrouping.build(
            libraryNovel = library,
            groupType = LibraryGroup.BY_TAG,
            librarySortingMode = 0,
            librarySortingAscending = true,
            collapsedDynamicCategories = emptySet(),
            collapsedDynamicAtBottom = false,
            unknownLabel = "Unknown",
            notTrackedLabel = "Not tracked",
        )
        assertEquals(listOf("Unknown"), result.keys.map { it.name })
    }

    // --- BY_AUTHOR ---------------------------------------------------------------------------

    @Test
    fun `BY_AUTHOR splits multi-author strings and de-duplicates author plus artist overlap`() {
        val library = listOf(
            libraryNovel(1, "Novel A", author = "Alice, Bob", artist = "Bob"),
            libraryNovel(2, "Novel B", author = "Carol"),
        )
        val result = NovelLibraryDynamicGrouping.build(
            libraryNovel = library,
            groupType = LibraryGroup.BY_AUTHOR,
            librarySortingMode = 0,
            librarySortingAscending = true,
            collapsedDynamicCategories = emptySet(),
            collapsedDynamicAtBottom = false,
            unknownLabel = "Unknown",
            notTrackedLabel = "Not tracked",
        )
        val byName = result.mapKeys { it.key.name }
        assertEquals(setOf("Alice", "Bob", "Carol"), byName.keys)
        assertEquals(listOf(1L), byName["Alice"]!!.map { it.libraryNovel.novel.id })
        assertEquals(listOf(1L), byName["Bob"]!!.map { it.libraryNovel.novel.id })
        assertEquals(listOf(2L), byName["Carol"]!!.map { it.libraryNovel.novel.id })
    }

    // --- BY_STATUS ---------------------------------------------------------------------------

    @Test
    fun `BY_STATUS uses pre-mapped status names`() {
        val library = listOf(libraryNovel(1, "A"), libraryNovel(2, "B"))
        val statusNames = mapOf(1L to "Ongoing", 2L to "Completed")
        val result = NovelLibraryDynamicGrouping.build(
            libraryNovel = library,
            groupType = LibraryGroup.BY_STATUS,
            librarySortingMode = 0,
            librarySortingAscending = true,
            collapsedDynamicCategories = emptySet(),
            collapsedDynamicAtBottom = false,
            unknownLabel = "Unknown",
            notTrackedLabel = "Not tracked",
            statusNames = statusNames,
        )
        val byName = result.mapKeys { it.key.name }
        assertEquals(setOf("Completed", "Ongoing"), byName.keys)
    }

    // --- BY_TRACK_STATUS ---------------------------------------------------------------------

    @Test
    fun `BY_TRACK_STATUS uses notTrackedLabel for novels without tracker entries`() {
        val library = listOf(libraryNovel(1, "A"), libraryNovel(2, "B"))
        val trackStatuses = mapOf(1L to "Reading")
        val result = NovelLibraryDynamicGrouping.build(
            libraryNovel = library,
            groupType = LibraryGroup.BY_TRACK_STATUS,
            librarySortingMode = 0,
            librarySortingAscending = true,
            collapsedDynamicCategories = emptySet(),
            collapsedDynamicAtBottom = false,
            unknownLabel = "Unknown",
            notTrackedLabel = "Not tracked",
            trackStatuses = trackStatuses,
        )
        val byName = result.mapKeys { it.key.name }
        assertEquals(setOf("Not tracked", "Reading"), byName.keys)
        assertEquals(listOf(1L), byName["Reading"]!!.map { it.libraryNovel.novel.id })
        assertEquals(listOf(2L), byName["Not tracked"]!!.map { it.libraryNovel.novel.id })
    }

    @Test
    fun `BY_TRACK_STATUS honors the trackingStatusOrder callback`() {
        val library = listOf(libraryNovel(1, "A"), libraryNovel(2, "B"), libraryNovel(3, "C"))
        val trackStatuses = mapOf(1L to "Dropped", 2L to "Reading", 3L to "Completed")
        val orderMap = mapOf("Reading" to "1", "Completed" to "5", "Dropped" to "6")
        val result = NovelLibraryDynamicGrouping.build(
            libraryNovel = library,
            groupType = LibraryGroup.BY_TRACK_STATUS,
            librarySortingMode = 0,
            librarySortingAscending = true,
            collapsedDynamicCategories = emptySet(),
            collapsedDynamicAtBottom = false,
            unknownLabel = "Unknown",
            notTrackedLabel = "Not tracked",
            trackStatuses = trackStatuses,
            trackingStatusOrder = { orderMap[it] ?: "7" },
        )
        val orderedNames = result.keys.map { it.name }
        assertEquals(listOf("Reading", "Completed", "Dropped"), orderedNames)
    }

    // --- collapse / dynamic-at-bottom --------------------------------------------------------

    @Test
    fun `collapsedDynamicCategories marks matching categories as isHidden`() {
        val library = listOf(libraryNovel(1, "Novel A"), libraryNovel(2, "Novel B"))
        val sourceMeta = mapOf(
            1L to ("Royal Road" to "royalroad"),
            2L to ("NovelBin" to "novelbin"),
        )
        val collapsedKey = "NovelBin${NovelCategory.sourceSplitter}novelbin"
        val result = NovelLibraryDynamicGrouping.build(
            libraryNovel = library,
            groupType = LibraryGroup.BY_SOURCE,
            librarySortingMode = 0,
            librarySortingAscending = true,
            collapsedDynamicCategories = setOf(collapsedKey),
            collapsedDynamicAtBottom = false,
            unknownLabel = "Unknown",
            notTrackedLabel = "Not tracked",
            sourceMeta = sourceMeta,
        )
        val byName = result.keys.associateBy { it.name }
        assertEquals(true, byName["NovelBin"]!!.isHidden)
        assertEquals(false, byName["Royal Road"]!!.isHidden)
    }

    @Test
    fun `collapsedDynamicAtBottom pushes hidden categories below visible ones`() {
        val library = listOf(libraryNovel(1, "A"), libraryNovel(2, "B"), libraryNovel(3, "C"))
        val sourceMeta = mapOf(
            1L to ("A-Source" to "a-source"),
            2L to ("B-Source" to "b-source"),
            3L to ("C-Source" to "c-source"),
        )
        val collapsedKey = "A-Source${NovelCategory.sourceSplitter}a-source"
        val result = NovelLibraryDynamicGrouping.build(
            libraryNovel = library,
            groupType = LibraryGroup.BY_SOURCE,
            librarySortingMode = 0,
            librarySortingAscending = true,
            collapsedDynamicCategories = setOf(collapsedKey),
            collapsedDynamicAtBottom = true,
            unknownLabel = "Unknown",
            notTrackedLabel = "Not tracked",
            sourceMeta = sourceMeta,
        )
        val orderedNames = result.keys.map { it.name }
        assertEquals(listOf("B-Source", "C-Source", "A-Source"), orderedNames)
    }

    // --- generic shape -----------------------------------------------------------------------

    @Test
    fun `synthetic categories get negative ids and isDynamic true`() {
        val library = listOf(libraryNovel(1, "Novel A"))
        val sourceMeta = mapOf(1L to ("Royal Road" to "royalroad"))
        val result = NovelLibraryDynamicGrouping.build(
            libraryNovel = library,
            groupType = LibraryGroup.BY_SOURCE,
            librarySortingMode = 0,
            librarySortingAscending = true,
            collapsedDynamicCategories = emptySet(),
            collapsedDynamicAtBottom = false,
            unknownLabel = "Unknown",
            notTrackedLabel = "Not tracked",
            sourceMeta = sourceMeta,
        )
        val category = result.keys.first()
        assertTrue(category.id != null && category.id!! < 0)
        assertTrue(category.isDynamic)
    }

    @Test
    fun `categories are sorted alphabetically and case-insensitive by default`() {
        val library = listOf(libraryNovel(1, "A"), libraryNovel(2, "B"), libraryNovel(3, "C"))
        val sourceMeta = mapOf(
            1L to ("zebra" to "z"),
            2L to ("Apple" to "a"),
            3L to ("Mango" to "m"),
        )
        val result = NovelLibraryDynamicGrouping.build(
            libraryNovel = library,
            groupType = LibraryGroup.BY_SOURCE,
            librarySortingMode = 0,
            librarySortingAscending = true,
            collapsedDynamicCategories = emptySet(),
            collapsedDynamicAtBottom = false,
            unknownLabel = "Unknown",
            notTrackedLabel = "Not tracked",
            sourceMeta = sourceMeta,
        )
        assertEquals(listOf("Apple", "Mango", "zebra"), result.keys.map { it.name })
    }

    // --- helpers -----------------------------------------------------------------------------

    private fun libraryNovel(
        novelId: Long,
        title: String,
        genres: List<String>? = null,
        author: String? = null,
        artist: String? = null,
        source: String = "test-source",
    ): LibraryNovel {
        val novel = Novel(
            id = novelId,
            source = source,
            url = "/n/$novelId",
            title = title,
            author = author,
            artist = artist,
            description = null,
            genres = genres,
            status = 0,
            thumbnailUrl = null,
            favorite = true,
            lastUpdate = 0L,
            initialized = true,
            chapterFlags = 0,
            dateAdded = 0L,
            updateStrategy = 0,
            coverLastModified = 0L,
        )
        return LibraryNovel(novel = novel)
    }
}
