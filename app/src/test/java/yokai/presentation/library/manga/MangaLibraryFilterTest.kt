package yokai.presentation.library.manga

import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.CategoryImpl
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.database.models.MangaImpl
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import yokai.presentation.library.manga.MangaLibraryFilter.MangaFilterState
import yokai.presentation.library.manga.MangaLibraryFilter.STATE_EXCLUDE
import yokai.presentation.library.manga.MangaLibraryFilter.STATE_IGNORE
import yokai.presentation.library.manga.MangaLibraryFilter.STATE_INCLUDE

class MangaLibraryFilterTest {

    @Test
    fun `no active filters returns library unchanged`() = runBlocking {
        val library = libraryOf("Default" to listOf(item(1)))
        val result = MangaLibraryFilter.filter(
            library = library,
            state = MangaFilterState(),
            sourceManager = mockSourceManager(),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
        )
        assertEquals(library, result)
    }

    @Test
    fun `unread include keeps only manga with unread chapters`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(item(1, unread = 5), item(2, unread = 0)),
        )
        val result = MangaLibraryFilter.filter(
            library = library,
            state = MangaFilterState(unread = STATE_INCLUDE),
            sourceManager = mockSourceManager(),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
        )
        assertEquals(listOf(1L), result.flatIds())
    }

    @Test
    fun `unread exclude keeps only manga with no unread chapters`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(item(1, unread = 5), item(2, unread = 0)),
        )
        val result = MangaLibraryFilter.filter(
            library = library,
            state = MangaFilterState(unread = STATE_EXCLUDE),
            sourceManager = mockSourceManager(),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
        )
        assertEquals(listOf(2L), result.flatIds())
    }

    @Test
    fun `unread value 3 keeps unread with no read history`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(
                item(1, unread = 5, read = 0),
                item(2, unread = 5, read = 10),
                item(3, unread = 0, read = 10),
            ),
        )
        val result = MangaLibraryFilter.filter(
            library = library,
            state = MangaFilterState(unread = 3),
            sourceManager = mockSourceManager(),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
        )
        assertEquals(listOf(1L), result.flatIds())
    }

    @Test
    fun `unread value 4 keeps unread with prior read history`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(
                item(1, unread = 5, read = 0),
                item(2, unread = 5, read = 10),
                item(3, unread = 0, read = 10),
            ),
        )
        val result = MangaLibraryFilter.filter(
            library = library,
            state = MangaFilterState(unread = 4),
            sourceManager = mockSourceManager(),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
        )
        assertEquals(listOf(2L), result.flatIds())
    }

    @Test
    fun `bookmarked include keeps only manga with bookmarks`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(item(1, bookmarkCount = 3), item(2, bookmarkCount = 0)),
        )
        val result = MangaLibraryFilter.filter(
            library = library,
            state = MangaFilterState(bookmarked = STATE_INCLUDE),
            sourceManager = mockSourceManager(),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
        )
        assertEquals(listOf(1L), result.flatIds())
    }

    @Test
    fun `bookmarked exclude keeps only manga without bookmarks`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(item(1, bookmarkCount = 3), item(2, bookmarkCount = 0)),
        )
        val result = MangaLibraryFilter.filter(
            library = library,
            state = MangaFilterState(bookmarked = STATE_EXCLUDE),
            sourceManager = mockSourceManager(),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
        )
        assertEquals(listOf(2L), result.flatIds())
    }

    @Test
    fun `completed include keeps only completed status manga`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(
                item(1, status = SManga.COMPLETED),
                item(2, status = SManga.ONGOING),
            ),
        )
        val result = MangaLibraryFilter.filter(
            library = library,
            state = MangaFilterState(completed = STATE_INCLUDE),
            sourceManager = mockSourceManager(),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
        )
        assertEquals(listOf(1L), result.flatIds())
    }

    @Test
    fun `completed exclude keeps only non-completed manga`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(
                item(1, status = SManga.COMPLETED),
                item(2, status = SManga.ONGOING),
            ),
        )
        val result = MangaLibraryFilter.filter(
            library = library,
            state = MangaFilterState(completed = STATE_EXCLUDE),
            sourceManager = mockSourceManager(),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
        )
        assertEquals(listOf(2L), result.flatIds())
    }

    @Test
    fun `downloaded include uses downloadCount field when present`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(
                item(1, downloadCount = 3L),
                item(2, downloadCount = 0L),
            ),
        )
        val result = MangaLibraryFilter.filter(
            library = library,
            state = MangaFilterState(downloaded = STATE_INCLUDE),
            sourceManager = mockSourceManager(),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 99 }, // should not be consulted because the field is non -1
            getTracks = { emptyList() },
        )
        assertEquals(listOf(1L), result.flatIds())
    }

    @Test
    fun `downloaded include falls back to lookup when downloadCount is unknown`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(item(1, downloadCount = -1L), item(2, downloadCount = -1L)),
        )
        val result = MangaLibraryFilter.filter(
            library = library,
            state = MangaFilterState(downloaded = STATE_INCLUDE),
            sourceManager = mockSourceManager(),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { manga -> if (manga.id == 1L) 5 else 0 },
            getTracks = { emptyList() },
        )
        assertEquals(listOf(1L), result.flatIds())
    }

    @Test
    fun `downloaded exclude keeps only undownloaded manga`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(item(1, downloadCount = 3L), item(2, downloadCount = 0L)),
        )
        val result = MangaLibraryFilter.filter(
            library = library,
            state = MangaFilterState(downloaded = STATE_EXCLUDE),
            sourceManager = mockSourceManager(),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
        )
        assertEquals(listOf(2L), result.flatIds())
    }

    @Test
    fun `downloaded include keeps local-source manga regardless of download count`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(item(1, sourceId = LocalSource.ID, downloadCount = 0L)),
        )
        val result = MangaLibraryFilter.filter(
            library = library,
            state = MangaFilterState(downloaded = STATE_INCLUDE),
            sourceManager = mockSourceManager(),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
        )
        assertEquals(listOf(1L), result.flatIds())
    }

    @Test
    fun `content type include keeps only SFW manga`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(item(1), item(2)),
        )
        val result = MangaLibraryFilter.filter(
            library = library,
            state = MangaFilterState(contentType = STATE_INCLUDE),
            sourceManager = mockSourceManager(),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
            isLewd = { it.id == 2L }, // item 2 is the NSFW one
        )
        assertEquals(listOf(1L), result.flatIds())
    }

    @Test
    fun `content type exclude keeps only NSFW manga`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(item(1), item(2)),
        )
        val result = MangaLibraryFilter.filter(
            library = library,
            state = MangaFilterState(contentType = STATE_EXCLUDE),
            sourceManager = mockSourceManager(),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
            isLewd = { it.id == 2L },
        )
        assertEquals(listOf(2L), result.flatIds())
    }

    @Test
    fun `manga type filter keeps only the selected type`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(
                item(1, genre = "Action"), // → TYPE_MANGA
                item(2, genre = "Manhua"), // → TYPE_MANHUA
            ),
        )
        val result = MangaLibraryFilter.filter(
            library = library,
            state = MangaFilterState(mangaType = Manga.TYPE_MANHUA),
            sourceManager = mockSourceManager(),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
        )
        assertEquals(listOf(2L), result.flatIds())
    }

    @Test
    fun `manga type MANHWA also keeps WEBTOON entries`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(
                item(1, genre = "Manhwa"),  // → TYPE_MANHWA
                item(2, sourceId = WEBTOON_SOURCE_ID), // resolves to TYPE_WEBTOON via source name
                item(3, genre = "Action"),  // → TYPE_MANGA
            ),
        )
        val result = MangaLibraryFilter.filter(
            library = library,
            state = MangaFilterState(mangaType = Manga.TYPE_MANHWA),
            sourceManager = mockSourceManager(),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
        )
        assertEquals(listOf(1L, 2L), result.flatIds())
    }

    @Test
    fun `tracker include drops manga that have no tracks`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(item(1), item(2)),
        )
        val result = MangaLibraryFilter.filter(
            library = library,
            state = MangaFilterState(tracked = STATE_INCLUDE),
            sourceManager = mockSourceManager(),
            loggedServiceNames = mapOf(1L to "AniList"),
            getDownloadCount = { 0 },
            getTracks = { mangaId -> if (mangaId == 1L) listOf(trackOn(serviceId = 1L)) else emptyList() },
        )
        assertEquals(listOf(1L), result.flatIds())
    }

    @Test
    fun `tracker exclude drops manga that have any track`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(item(1), item(2)),
        )
        val result = MangaLibraryFilter.filter(
            library = library,
            state = MangaFilterState(tracked = STATE_EXCLUDE),
            sourceManager = mockSourceManager(),
            loggedServiceNames = mapOf(1L to "AniList"),
            getDownloadCount = { 0 },
            getTracks = { mangaId -> if (mangaId == 1L) listOf(trackOn(serviceId = 1L)) else emptyList() },
        )
        assertEquals(listOf(2L), result.flatIds())
    }

    @Test
    fun `tracker include with specific service drops manga not tracked on that service`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(item(1), item(2)),
        )
        val result = MangaLibraryFilter.filter(
            library = library,
            state = MangaFilterState(tracked = STATE_INCLUDE, tracker = "MyAnimeList"),
            sourceManager = mockSourceManager(),
            loggedServiceNames = mapOf(1L to "AniList", 2L to "MyAnimeList"),
            getDownloadCount = { 0 },
            getTracks = { mangaId ->
                when (mangaId) {
                    1L -> listOf(trackOn(serviceId = 2L))
                    2L -> listOf(trackOn(serviceId = 1L))
                    else -> emptyList()
                }
            },
        )
        assertEquals(listOf(1L), result.flatIds())
    }

    @Test
    fun `tracker exclude with specific service drops manga tracked on that service`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(item(1), item(2)),
        )
        val result = MangaLibraryFilter.filter(
            library = library,
            state = MangaFilterState(tracked = STATE_EXCLUDE, tracker = "MyAnimeList"),
            sourceManager = mockSourceManager(),
            loggedServiceNames = mapOf(1L to "AniList", 2L to "MyAnimeList"),
            getDownloadCount = { 0 },
            getTracks = { mangaId ->
                when (mangaId) {
                    1L -> listOf(trackOn(serviceId = 2L))
                    2L -> listOf(trackOn(serviceId = 1L))
                    else -> emptyList()
                }
            },
        )
        assertEquals(listOf(2L), result.flatIds())
    }

    @Test
    fun `empty categories are dropped from the filtered result`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(item(1, unread = 5)),
            "Empty" to listOf(item(2, unread = 0)),
        )
        val result = MangaLibraryFilter.filter(
            library = library,
            state = MangaFilterState(unread = STATE_INCLUDE),
            sourceManager = mockSourceManager(),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
        )
        assertEquals(1, result.size)
        assertEquals("Default", result.keys.first().name)
    }

    @Test
    fun `combining unread include and bookmarked include narrows to both`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(
                item(1, unread = 5, bookmarkCount = 3),
                item(2, unread = 5, bookmarkCount = 0),
                item(3, unread = 0, bookmarkCount = 3),
            ),
        )
        val result = MangaLibraryFilter.filter(
            library = library,
            state = MangaFilterState(unread = STATE_INCLUDE, bookmarked = STATE_INCLUDE),
            sourceManager = mockSourceManager(),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
        )
        assertEquals(listOf(1L), result.flatIds())
    }

    @Test
    fun `isAnyActive is false on the default state`() {
        assertEquals(false, MangaFilterState().isAnyActive)
    }

    @Test
    fun `isAnyActive is true when any filter is set`() {
        assertEquals(true, MangaFilterState(unread = STATE_INCLUDE).isAnyActive)
        assertEquals(true, MangaFilterState(mangaType = Manga.TYPE_MANHWA).isAnyActive)
    }

    @Test
    fun `detectMangaTypes returns empty when only TYPE_MANGA is present`() {
        val library = libraryOf("Default" to listOf(item(1, genre = "Action")))
        val result = MangaLibraryFilter.detectMangaTypes(library, mockSourceManager())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `detectMangaTypes finds MANHWA and MANHUA`() {
        val library = libraryOf(
            "Default" to listOf(
                item(1, genre = "Manhwa"),
                item(2, genre = "Manhua"),
            ),
        )
        val result = MangaLibraryFilter.detectMangaTypes(library, mockSourceManager())
        assertEquals(setOf(Manga.TYPE_MANHWA, Manga.TYPE_MANHUA), result)
    }

    @Test
    fun `detectMangaTypes folds WEBTOON into MANHWA`() {
        val library = libraryOf(
            "Default" to listOf(item(1, sourceId = WEBTOON_SOURCE_ID)),
        )
        val result = MangaLibraryFilter.detectMangaTypes(library, mockSourceManager())
        assertEquals(setOf(Manga.TYPE_MANHWA), result)
    }

    @Test
    fun `detectMangaTypes stops after collecting all three buckets`() {
        val library = libraryOf(
            "Default" to listOf(
                item(1, genre = "Manhwa"),
                item(2, genre = "Manhua"),
                item(3, genre = "Comic"),
                item(4, genre = "Manhwa"), // would be a duplicate add; loop should return early
            ),
        )
        val result = MangaLibraryFilter.detectMangaTypes(library, mockSourceManager())
        assertEquals(3, result.size)
    }

    @Test
    fun `keepEmptyCategories preserves categories whose items were entirely filtered out`() = runBlocking {
        val library = libraryOf(
            "Reading" to listOf(item(1, unread = 5)),
            "Completed" to listOf(item(2, unread = 0)),
        )
        val result = MangaLibraryFilter.filter(
            library = library,
            state = MangaFilterState(unread = STATE_INCLUDE),
            sourceManager = mockSourceManager(),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
            keepEmptyCategories = true,
        )
        // "Completed" had its one item filtered out but the key stays.
        assertEquals(2, result.size)
        assertEquals(listOf(1L), result.flatIds())
        assertTrue(result.values.any { it.isEmpty() })
    }

    @Test
    fun `keepEmptyCategories defaults to false so empties drop`() = runBlocking {
        val library = libraryOf(
            "Reading" to listOf(item(1, unread = 5)),
            "Completed" to listOf(item(2, unread = 0)),
        )
        val result = MangaLibraryFilter.filter(
            library = library,
            state = MangaFilterState(unread = STATE_INCLUDE),
            sourceManager = mockSourceManager(),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
        )
        assertEquals(1, result.size)
        assertEquals(listOf(1L), result.flatIds())
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun Map<Category, List<LibraryItem.Manga>>.flatIds(): List<Long> =
        values.flatten().mapNotNull { it.libraryManga.manga.id }.sorted()

    private fun libraryOf(
        vararg pairs: Pair<String, List<LibraryItem.Manga>>,
    ): Map<Category, List<LibraryItem.Manga>> = pairs.associate { (name, items) ->
        CategoryImpl().apply {
            id = name.hashCode()
            this.name = name
            order = 0
        } to items
    }

    /**
     * Uses [MangaImpl] (with `favorite = false`) instead of a mock so the interface's default
     * `is*Tag` member methods used by `seriesType` run their real impl. MockK relaxed mocks
     * would otherwise return `false` for those calls and break type-detection tests. Default
     * `sourceId = 100L` keeps `isLocal()` false; LocalSource.ID is 0.
     */
    private fun item(
        mangaId: Long,
        unread: Int = 0,
        read: Int = 0,
        bookmarkCount: Int = 0,
        status: Int = SManga.UNKNOWN,
        genre: String? = null,
        sourceId: Long = 100L,
        downloadCount: Long = -1L,
    ): LibraryItem.Manga {
        val manga = MangaImpl(id = mangaId, source = sourceId, url = "url-$mangaId").apply {
            ogTitle = "title-$mangaId"
            ogGenre = genre
            ogStatus = status
        }
        return LibraryItem.Manga(
            libraryManga = LibraryManga(
                manga = manga,
                unread = unread,
                read = read,
                bookmarkCount = bookmarkCount,
            ),
            downloadCount = downloadCount,
        )
    }

    private fun trackOn(serviceId: Long): Track =
        mockk<Track>(relaxed = true).also { every { it.sync_id } returns serviceId }

    private fun mockSourceManager(): SourceManager {
        val sm = mockk<SourceManager>(relaxed = true)
        val genericStub = mockk<Source>(relaxed = true)
        every { genericStub.name } returns "GenericSource"
        val webtoonStub = mockk<Source>(relaxed = true)
        every { webtoonStub.name } returns "Webtoons"
        every { sm.getOrStub(any()) } answers {
            if (firstArg<Long>() == WEBTOON_SOURCE_ID) webtoonStub else genericStub
        }
        return sm
    }

    private companion object {
        const val WEBTOON_SOURCE_ID = 1001L
    }
}
