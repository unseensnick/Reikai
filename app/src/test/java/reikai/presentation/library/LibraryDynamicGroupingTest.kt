package reikai.presentation.library

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import reikai.presentation.library.ReikaiDynamicCategory.SOURCE_SPLITTER
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.manga.model.Manga

class LibraryDynamicGroupingTest {

    @Test
    fun `empty library returns empty`() {
        build(emptyList(), LibraryGroup.BY_SOURCE).isEmpty() shouldBe true
    }

    @Test
    fun `BY_DEFAULT returns empty (caller routes through Mihon grouping)`() {
        build(listOf(libraryManga(1)), LibraryGroup.BY_DEFAULT).isEmpty() shouldBe true
    }

    @Test
    fun `categorySortOrder Z to A reverses dynamic groups`() {
        val result = build(
            listOf(libraryManga(1), libraryManga(2)),
            LibraryGroup.BY_SOURCE,
            sourceMeta = mapOf(1L to ("Alpha" to 1L), 2L to ("Beta" to 2L)),
            categorySortOrder = 2,
        )
        result.keys.map { ReikaiDynamicCategory.displayName(it) } shouldContainExactly listOf("Beta", "Alpha")
    }

    @Test
    fun `categorySortOrder reorders BY_STATUS groups A to Z`() {
        val result = build(
            listOf(libraryManga(1), libraryManga(2)),
            LibraryGroup.BY_STATUS,
            statusNames = mapOf(1L to "Ongoing", 2L to "Completed"),
            categorySortOrder = 1,
        )
        result.keys.map { ReikaiDynamicCategory.displayName(it) } shouldContainExactly listOf("Completed", "Ongoing")
    }

    @Test
    fun `BY_TRACK_STATUS keeps progress order under an A to Z category sort`() {
        // A->Z would alphabetize to Completed, Reading; track status stays in reading-progress order.
        val result = build(
            listOf(libraryManga(1), libraryManga(2)),
            LibraryGroup.BY_TRACK_STATUS,
            trackStatuses = mapOf(1L to "Completed", 2L to "Reading"),
            trackingStatusOrder = { mapOf("Reading" to "1", "Completed" to "5")[it] ?: "9" },
            categorySortOrder = 1,
        )
        result.keys.map { ReikaiDynamicCategory.displayName(it) } shouldContainExactly listOf("Reading", "Completed")
    }

    @Test
    fun `BY_TRACK_STATUS keeps progress order under a Z to A category sort`() {
        // Z->A no longer reverses track status: it stays in reading-progress order, not reversed.
        val result = build(
            listOf(libraryManga(1), libraryManga(2)),
            LibraryGroup.BY_TRACK_STATUS,
            trackStatuses = mapOf(1L to "Completed", 2L to "Reading"),
            trackingStatusOrder = { mapOf("Reading" to "1", "Completed" to "5")[it] ?: "9" },
            categorySortOrder = 2,
        )
        result.keys.map { ReikaiDynamicCategory.displayName(it) } shouldContainExactly listOf("Reading", "Completed")
    }

    @Test
    fun `UNGROUPED returns one flat bucket with every manga`() {
        val result = build(listOf(libraryManga(1), libraryManga(2), libraryManga(3)), LibraryGroup.UNGROUPED)
        result.size shouldBe 1
        result.values.first() shouldContainExactlyInAnyOrder listOf(1L, 2L, 3L)
    }

    @Test
    fun `BY_SOURCE groups by source name and encodes the source id`() {
        val result = build(
            listOf(libraryManga(1), libraryManga(2), libraryManga(3)),
            LibraryGroup.BY_SOURCE,
            sourceMeta = mapOf(1L to ("MangaDex" to 100L), 2L to ("Webtoons" to 200L), 3L to ("MangaDex" to 100L)),
        )
        val byName = result.mapKeys { ReikaiDynamicCategory.displayName(it.key) }
        byName.keys shouldBe setOf("MangaDex", "Webtoons")
        byName.getValue("MangaDex") shouldContainExactlyInAnyOrder listOf(1L, 3L)
        byName.getValue("Webtoons") shouldContainExactly listOf(2L)
        ReikaiDynamicCategory.sourceId(
            result.keys.first {
                ReikaiDynamicCategory.displayName(it) == "MangaDex"
            },
        ) shouldBe
            100L
    }

    @Test
    fun `BY_SOURCE buckets missing-source manga under unknownLabel`() {
        val result = build(listOf(libraryManga(1)), LibraryGroup.BY_SOURCE, sourceMeta = emptyMap())
        result.keys.map { ReikaiDynamicCategory.displayName(it) } shouldContainExactly listOf("Unknown")
    }

    @Test
    fun `BY_LANGUAGE groups by language code and stores langId`() {
        val result = build(
            listOf(libraryManga(1), libraryManga(2)),
            LibraryGroup.BY_LANGUAGE,
            languageCodes = mapOf(1L to "en", 2L to "ja"),
            languageDisplay = { if (it == "en") "English" else "Japanese" },
        )
        val byLangId = result.keys.associateBy { ReikaiDynamicCategory.langId(it) }
        byLangId.keys shouldBe setOf("en", "ja")
        ReikaiDynamicCategory.displayName(byLangId.getValue("en")) shouldBe "English"
        ReikaiDynamicCategory.displayName(byLangId.getValue("ja")) shouldBe "Japanese"
    }

    @Test
    fun `BY_TAG places manga in every matching tag and capitalizes`() {
        val result = build(
            listOf(
                libraryManga(1, genre = listOf("Action", "Romance")),
                libraryManga(2, genre = listOf("Romance", "Slice of Life")),
            ),
            LibraryGroup.BY_TAG,
        )
        val byName = result.mapKeys { ReikaiDynamicCategory.displayName(it.key) }
        byName.keys shouldBe setOf("Action", "Romance", "Slice Of Life")
        byName.getValue("Action") shouldContainExactly listOf(1L)
        byName.getValue("Romance") shouldContainExactlyInAnyOrder listOf(1L, 2L)
        byName.getValue("Slice Of Life") shouldContainExactly listOf(2L)
    }

    @Test
    fun `BY_TAG with no genre buckets under unknownLabel`() {
        val result = build(listOf(libraryManga(1, genre = null)), LibraryGroup.BY_TAG)
        result.keys.map { ReikaiDynamicCategory.displayName(it) } shouldContainExactly listOf("Unknown")
    }

    @Test
    fun `BY_AUTHOR splits multi-author strings and de-duplicates author plus artist overlap`() {
        val result = build(
            listOf(
                libraryManga(1, author = "Alice, Bob", artist = "Bob"),
                libraryManga(2, author = "Carol"),
            ),
            LibraryGroup.BY_AUTHOR,
        )
        val byName = result.mapKeys { ReikaiDynamicCategory.displayName(it.key) }
        byName.keys shouldBe setOf("Alice", "Bob", "Carol")
        byName.getValue("Alice") shouldContainExactly listOf(1L)
        byName.getValue("Bob") shouldContainExactly listOf(1L)
        byName.getValue("Carol") shouldContainExactly listOf(2L)
    }

    @Test
    fun `BY_STATUS uses pre-mapped status names`() {
        val result = build(
            listOf(libraryManga(1), libraryManga(2)),
            LibraryGroup.BY_STATUS,
            statusNames = mapOf(1L to "Ongoing", 2L to "Completed"),
        )
        result.keys.map { ReikaiDynamicCategory.displayName(it) } shouldBe listOf("Completed", "Ongoing")
    }

    @Test
    fun `BY_TRACK_STATUS uses notTrackedLabel for untracked manga`() {
        val result = build(
            listOf(libraryManga(1), libraryManga(2)),
            LibraryGroup.BY_TRACK_STATUS,
            trackStatuses = mapOf(1L to "Reading"),
        )
        val byName = result.mapKeys { ReikaiDynamicCategory.displayName(it.key) }
        byName.keys shouldBe setOf("Not tracked", "Reading")
        byName.getValue("Reading") shouldContainExactly listOf(1L)
        byName.getValue("Not tracked") shouldContainExactly listOf(2L)
    }

    @Test
    fun `BY_TRACK_STATUS honors the trackingStatusOrder callback`() {
        val orderMap = mapOf("Reading" to "1", "Completed" to "5", "Dropped" to "6")
        val result = build(
            listOf(libraryManga(1), libraryManga(2), libraryManga(3)),
            LibraryGroup.BY_TRACK_STATUS,
            trackStatuses = mapOf(1L to "Dropped", 2L to "Reading", 3L to "Completed"),
            trackingStatusOrder = { orderMap[it] ?: "7" },
        )
        result.keys.map { ReikaiDynamicCategory.displayName(it) } shouldBe listOf("Reading", "Completed", "Dropped")
    }

    @Test
    fun `collapsedDynamicAtBottom pushes collapsed groups below visible ones`() {
        val result = build(
            listOf(libraryManga(1), libraryManga(2), libraryManga(3)),
            LibraryGroup.BY_SOURCE,
            sourceMeta = mapOf(1L to ("A-Source" to 100L), 2L to ("B-Source" to 200L), 3L to ("C-Source" to 300L)),
            collapsedDynamicCategories = setOf("A-Source${SOURCE_SPLITTER}100"),
            collapsedDynamicAtBottom = true,
        )
        result.keys.map { ReikaiDynamicCategory.displayName(it) } shouldBe listOf("B-Source", "C-Source", "A-Source")
    }

    @Test
    fun `headerKey is the encoded collapse key`() {
        val result = build(
            listOf(libraryManga(1)),
            LibraryGroup.BY_SOURCE,
            sourceMeta = mapOf(1L to ("Webtoons" to 200L)),
        )
        ReikaiDynamicCategory.headerKey(result.keys.first()) shouldBe "Webtoons${SOURCE_SPLITTER}200"
    }

    @Test
    fun `synthetic categories get negative ids and read as dynamic`() {
        val result = build(
            listOf(libraryManga(1)),
            LibraryGroup.BY_SOURCE,
            sourceMeta = mapOf(1L to ("MangaDex" to 100L)),
        )
        val category = result.keys.first()
        (category.id < 0) shouldBe true
        ReikaiDynamicCategory.isDynamic(category) shouldBe true
    }

    @Test
    fun `categories sort alphabetically and case-insensitively by default`() {
        val result = build(
            listOf(libraryManga(1), libraryManga(2), libraryManga(3)),
            LibraryGroup.BY_SOURCE,
            sourceMeta = mapOf(1L to ("zebra" to 100L), 2L to ("Apple" to 200L), 3L to ("Mango" to 300L)),
        )
        result.keys.map { ReikaiDynamicCategory.displayName(it) } shouldBe listOf("Apple", "Mango", "zebra")
    }

    // A mixed manga-plus-novel list buckets by EntryId, since a raw row id is only unique within one
    // content type. These pin that the kernel is safe over that id space, which is what lets the shared
    // library engine group one interleaved list.

    @Test
    fun `a manga and a novel sharing a row id stay distinct entries`() {
        val manga = EntryId.Manga(12)
        val novel = EntryId.Novel(12)
        val result = LibraryDynamicGrouping.build(
            items = listOf(
                DynItem(manga, genre = null, author = "Ito", artist = null),
                DynItem(novel, genre = null, author = "Ito", artist = null),
            ),
            groupType = LibraryGroup.BY_AUTHOR,
            inheritedSortFlag = LibrarySort.default.flag,
            collapsedDynamicCategories = emptySet(),
            collapsedDynamicAtBottom = false,
            unknownLabel = "Unknown",
            notTrackedLabel = "Not tracked",
        )
        // Both land in the one author bucket: de-duplication must not swallow one as the other.
        result.values.single() shouldContainExactlyInAnyOrder listOf(manga, novel)
    }

    @Test
    fun `metadata does not cross-read between a manga and a novel sharing a row id`() {
        val manga = EntryId.Manga(12)
        val novel = EntryId.Novel(12)
        val result = LibraryDynamicGrouping.build(
            items = listOf(
                DynItem(manga, genre = null, author = null, artist = null),
                DynItem(novel, genre = null, author = null, artist = null),
            ),
            groupType = LibraryGroup.BY_SOURCE,
            inheritedSortFlag = LibrarySort.default.flag,
            collapsedDynamicCategories = emptySet(),
            collapsedDynamicAtBottom = false,
            unknownLabel = "Unknown",
            notTrackedLabel = "Not tracked",
            sourceMeta = mapOf(manga to ("MangaDex" to "100"), novel to ("Royal Road" to "royalroad")),
        )
        result.entries.associate { (category, ids) -> ReikaiDynamicCategory.displayName(category) to ids } shouldBe
            mapOf("MangaDex" to listOf(manga), "Royal Road" to listOf(novel))
    }

    private fun build(
        library: List<LibraryManga>,
        groupType: Int,
        sourceMeta: Map<Long, Pair<String, Long>> = emptyMap(),
        trackStatuses: Map<Long, String> = emptyMap(),
        languageCodes: Map<Long, String> = emptyMap(),
        statusNames: Map<Long, String> = emptyMap(),
        languageDisplay: (String) -> String = { it },
        trackingStatusOrder: (String) -> String = { it },
        collapsedDynamicCategories: Set<String> = emptySet(),
        collapsedDynamicAtBottom: Boolean = false,
        categorySortOrder: Int = 0,
    ) = LibraryDynamicGrouping.build(
        items = library.map { DynItem(it.manga.id, it.manga.genre, it.manga.author, it.manga.artist) },
        groupType = groupType,
        inheritedSortFlag = LibrarySort.default.flag,
        collapsedDynamicCategories = collapsedDynamicCategories,
        collapsedDynamicAtBottom = collapsedDynamicAtBottom,
        categorySortOrder = categorySortOrder,
        unknownLabel = "Unknown",
        notTrackedLabel = "Not tracked",
        sourceMeta = sourceMeta.mapValues { (_, v) -> v.first to v.second.toString() },
        trackStatuses = trackStatuses,
        languageCodes = languageCodes,
        statusNames = statusNames,
        languageDisplay = languageDisplay,
        trackingStatusOrder = trackingStatusOrder,
    )

    private fun libraryManga(
        id: Long,
        title: String = "Manga $id",
        genre: List<String>? = null,
        author: String? = null,
        artist: String? = null,
        source: Long = 100L,
    ): LibraryManga {
        val manga = Manga.create().copy(
            id = id,
            source = source,
            title = title,
            genre = genre,
            author = author,
            artist = artist,
        )
        return LibraryManga(
            manga = manga,
            categories = emptyList(),
            totalChapters = 0,
            readCount = 0,
            bookmarkCount = 0,
            latestUpload = 0,
            chapterFetchedAt = 0,
            lastRead = 0,
        )
    }
}
