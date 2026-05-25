package yokai.presentation.library.manga

import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.database.models.MangaImpl
import eu.kanade.tachiyomi.ui.library.LibraryGroup
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MangaLibraryDynamicGroupingTest {

    @Test
    fun `empty library returns empty`() {
        val result = MangaLibraryDynamicGrouping.build(
            libraryManga = emptyList(),
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
    fun `BY_DEFAULT returns empty (caller routes through MangaLibrarySectioner)`() {
        val library = listOf(libraryManga(1, "X"))
        val result = MangaLibraryDynamicGrouping.build(
            libraryManga = library,
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

    // --- BY_SOURCE -----------------------------------------------------------------------------

    @Test
    fun `BY_SOURCE groups manga by source name and encodes the source id`() {
        val library = listOf(
            libraryManga(1, "Manga A"),
            libraryManga(2, "Manga B"),
            libraryManga(3, "Manga C"),
        )
        val sourceMeta = mapOf(
            1L to ("MangaDex" to 100L),
            2L to ("Webtoons" to 200L),
            3L to ("MangaDex" to 100L),
        )
        val result = MangaLibraryDynamicGrouping.build(
            libraryManga = library,
            groupType = LibraryGroup.BY_SOURCE,
            librarySortingMode = 0,
            librarySortingAscending = true,
            collapsedDynamicCategories = emptySet(),
            collapsedDynamicAtBottom = false,
            unknownLabel = "Unknown",
            notTrackedLabel = "Not tracked",
            sourceMeta = sourceMeta,
        )
        // Two categories (MangaDex, Webtoons). MangaDex contains manga 1 and 3; Webtoons has 2.
        val byName = result.mapKeys { it.key.name }
        assertEquals(setOf("MangaDex", "Webtoons"), byName.keys)
        assertEquals(setOf(1L, 3L), byName["MangaDex"]!!.mapNotNull { it.libraryManga.manga.id }.toSet())
        assertEquals(listOf(2L), byName["Webtoons"]!!.map { it.libraryManga.manga.id })
        // sourceId round-trips into the synthetic Category.
        val mangaDex = result.keys.first { it.name == "MangaDex" }
        assertEquals(100L, mangaDex.sourceId)
    }

    @Test
    fun `BY_SOURCE buckets missing-source manga under unknownLabel`() {
        val library = listOf(libraryManga(1, "Manga A"))
        val result = MangaLibraryDynamicGrouping.build(
            libraryManga = library,
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

    // --- BY_LANGUAGE ---------------------------------------------------------------------------

    @Test
    fun `BY_LANGUAGE groups manga by language code and stores langId on category`() {
        val library = listOf(
            libraryManga(1, "Manga A"),
            libraryManga(2, "Manga B"),
        )
        val languageCodes = mapOf(1L to "en", 2L to "ja")
        val result = MangaLibraryDynamicGrouping.build(
            libraryManga = library,
            groupType = LibraryGroup.BY_LANGUAGE,
            librarySortingMode = 0,
            librarySortingAscending = true,
            collapsedDynamicCategories = emptySet(),
            collapsedDynamicAtBottom = false,
            unknownLabel = "Unknown",
            notTrackedLabel = "Not tracked",
            languageCodes = languageCodes,
            languageDisplay = { code -> if (code == "en") "English" else "Japanese" },
        )
        val byLangId = result.keys.associateBy { it.langId }
        assertEquals(setOf("en", "ja"), byLangId.keys)
        assertEquals("English", byLangId["en"]!!.name)
        assertEquals("Japanese", byLangId["ja"]!!.name)
    }

    @Test
    fun `BY_LANGUAGE buckets missing-language manga under unknownLabel`() {
        val library = listOf(libraryManga(1, "Manga A"))
        val result = MangaLibraryDynamicGrouping.build(
            libraryManga = library,
            groupType = LibraryGroup.BY_LANGUAGE,
            librarySortingMode = 0,
            librarySortingAscending = true,
            collapsedDynamicCategories = emptySet(),
            collapsedDynamicAtBottom = false,
            unknownLabel = "Unknown",
            notTrackedLabel = "Not tracked",
        )
        assertEquals(listOf("Unknown"), result.keys.map { it.name })
    }

    // --- BY_TAG --------------------------------------------------------------------------------

    @Test
    fun `BY_TAG splits comma-separated genres and places manga in every matching tag`() {
        val library = listOf(
            libraryManga(1, "Manga A", genre = "Action, Romance"),
            libraryManga(2, "Manga B", genre = "Romance, Slice of Life"),
        )
        val result = MangaLibraryDynamicGrouping.build(
            libraryManga = library,
            groupType = LibraryGroup.BY_TAG,
            librarySortingMode = 0,
            librarySortingAscending = true,
            collapsedDynamicCategories = emptySet(),
            collapsedDynamicAtBottom = false,
            unknownLabel = "Unknown",
            notTrackedLabel = "Not tracked",
        )
        val byName = result.mapKeys { it.key.name }
        // Tags are capitalized via the existing String.capitalizeWords extension.
        assertEquals(setOf("Action", "Romance", "Slice Of Life"), byName.keys)
        assertEquals(listOf(1L), byName["Action"]!!.map { it.libraryManga.manga.id })
        assertEquals(setOf(1L, 2L), byName["Romance"]!!.mapNotNull { it.libraryManga.manga.id }.toSet())
        assertEquals(listOf(2L), byName["Slice Of Life"]!!.map { it.libraryManga.manga.id })
    }

    @Test
    fun `BY_TAG with blank genre buckets under unknownLabel`() {
        val library = listOf(libraryManga(1, "Manga A", genre = null))
        val result = MangaLibraryDynamicGrouping.build(
            libraryManga = library,
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

    // --- BY_AUTHOR -----------------------------------------------------------------------------

    @Test
    fun `BY_AUTHOR splits multi-author strings and de-duplicates author plus artist overlap`() {
        val library = listOf(
            libraryManga(1, "Manga A", author = "Alice, Bob", artist = "Bob"),
            libraryManga(2, "Manga B", author = "Carol"),
        )
        val result = MangaLibraryDynamicGrouping.build(
            libraryManga = library,
            groupType = LibraryGroup.BY_AUTHOR,
            librarySortingMode = 0,
            librarySortingAscending = true,
            collapsedDynamicCategories = emptySet(),
            collapsedDynamicAtBottom = false,
            unknownLabel = "Unknown",
            notTrackedLabel = "Not tracked",
        )
        val byName = result.mapKeys { it.key.name }
        // Alice / Bob / Carol. Bob appears once even though it shows up in author and artist.
        assertEquals(setOf("Alice", "Bob", "Carol"), byName.keys)
        assertEquals(listOf(1L), byName["Alice"]!!.map { it.libraryManga.manga.id })
        assertEquals(listOf(1L), byName["Bob"]!!.map { it.libraryManga.manga.id })
        assertEquals(listOf(2L), byName["Carol"]!!.map { it.libraryManga.manga.id })
    }

    // --- BY_STATUS -----------------------------------------------------------------------------

    @Test
    fun `BY_STATUS uses pre-mapped status names`() {
        val library = listOf(
            libraryManga(1, "Manga A"),
            libraryManga(2, "Manga B"),
        )
        val statusNames = mapOf(1L to "Ongoing", 2L to "Completed")
        val result = MangaLibraryDynamicGrouping.build(
            libraryManga = library,
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

    // --- BY_TRACK_STATUS ----------------------------------------------------------------------

    @Test
    fun `BY_TRACK_STATUS uses notTrackedLabel for manga without tracker entries`() {
        val library = listOf(
            libraryManga(1, "Manga A"),
            libraryManga(2, "Manga B"),
        )
        val trackStatuses = mapOf(1L to "Reading")
        val result = MangaLibraryDynamicGrouping.build(
            libraryManga = library,
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
        assertEquals(listOf(1L), byName["Reading"]!!.map { it.libraryManga.manga.id })
        assertEquals(listOf(2L), byName["Not tracked"]!!.map { it.libraryManga.manga.id })
    }

    @Test
    fun `BY_TRACK_STATUS honors the trackingStatusOrder callback`() {
        val library = listOf(
            libraryManga(1, "A"),
            libraryManga(2, "B"),
            libraryManga(3, "C"),
        )
        val trackStatuses = mapOf(
            1L to "Dropped",
            2L to "Reading",
            3L to "Completed",
        )
        val orderMap = mapOf("Reading" to "1", "Completed" to "5", "Dropped" to "6")
        val result = MangaLibraryDynamicGrouping.build(
            libraryManga = library,
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

    // --- collapse / dynamic-at-bottom ----------------------------------------------------------

    @Test
    fun `collapsedDynamicCategories marks matching categories as isHidden`() {
        val library = listOf(
            libraryManga(1, "Manga A"),
            libraryManga(2, "Manga B"),
        )
        val sourceMeta = mapOf(
            1L to ("MangaDex" to 100L),
            2L to ("Webtoons" to 200L),
        )
        val collapsedKey = "Webtoons${Category.sourceSplitter}200"
        val result = MangaLibraryDynamicGrouping.build(
            libraryManga = library,
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
        assertEquals(true, byName["Webtoons"]!!.isHidden)
        assertEquals(false, byName["MangaDex"]!!.isHidden)
    }

    @Test
    fun `collapsedDynamicAtBottom pushes hidden categories below visible ones`() {
        val library = listOf(
            libraryManga(1, "A"),
            libraryManga(2, "B"),
            libraryManga(3, "C"),
        )
        val sourceMeta = mapOf(
            1L to ("A-Source" to 100L),
            2L to ("B-Source" to 200L),
            3L to ("C-Source" to 300L),
        )
        // Hide A-Source. Without the toggle it sorts to position 0 (alphabetical); with it, last.
        val collapsedKey = "A-Source${Category.sourceSplitter}100"
        val result = MangaLibraryDynamicGrouping.build(
            libraryManga = library,
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

    // --- generic shape -------------------------------------------------------------------------

    @Test
    fun `synthetic categories get negative ids and isDynamic true`() {
        val library = listOf(libraryManga(1, "Manga A"))
        val sourceMeta = mapOf(1L to ("MangaDex" to 100L))
        val result = MangaLibraryDynamicGrouping.build(
            libraryManga = library,
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
        val library = listOf(
            libraryManga(1, "A"),
            libraryManga(2, "B"),
            libraryManga(3, "C"),
        )
        val sourceMeta = mapOf(
            1L to ("zebra" to 100L),
            2L to ("Apple" to 200L),
            3L to ("Mango" to 300L),
        )
        val result = MangaLibraryDynamicGrouping.build(
            libraryManga = library,
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

    // --- helpers -------------------------------------------------------------------------------

    private fun libraryManga(
        mangaId: Long,
        title: String,
        genre: String? = null,
        author: String? = null,
        artist: String? = null,
        sourceId: Long = 100L,
    ): LibraryManga {
        val manga = MangaImpl(id = mangaId, source = sourceId, url = "url-$mangaId").apply {
            ogTitle = title
            ogGenre = genre
            ogAuthor = author
            ogArtist = artist
        }
        return LibraryManga(manga = manga)
    }
}
