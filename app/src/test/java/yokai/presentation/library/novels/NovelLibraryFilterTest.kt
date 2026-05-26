package yokai.presentation.library.novels

import eu.kanade.tachiyomi.data.database.models.LibraryNovel
import eu.kanade.tachiyomi.data.database.models.NovelCategory
import eu.kanade.tachiyomi.data.database.models.NovelCategoryImpl
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import yokai.data.novel.NovelStatusCode
import yokai.domain.novel.models.Novel
import yokai.domain.novel.models.NovelTrack
import yokai.presentation.library.novels.NovelLibraryFilter.NovelFilterState
import yokai.presentation.library.novels.NovelLibraryFilter.STATE_EXCLUDE
import yokai.presentation.library.novels.NovelLibraryFilter.STATE_INCLUDE

class NovelLibraryFilterTest {

    @Test
    fun `no active filters returns library unchanged`() = runBlocking {
        val library = libraryOf("Default" to listOf(item(1)))
        val result = NovelLibraryFilter.filter(
            library = library,
            state = NovelFilterState(),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
        )
        assertEquals(library, result)
    }

    @Test
    fun `unread include keeps only novels with unread chapters`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(item(1, unread = 5), item(2, unread = 0)),
        )
        val result = NovelLibraryFilter.filter(
            library = library,
            state = NovelFilterState(unread = STATE_INCLUDE),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
        )
        assertEquals(listOf(1L), result.flatIds())
    }

    @Test
    fun `unread exclude keeps only novels with no unread chapters`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(item(1, unread = 5), item(2, unread = 0)),
        )
        val result = NovelLibraryFilter.filter(
            library = library,
            state = NovelFilterState(unread = STATE_EXCLUDE),
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
        val result = NovelLibraryFilter.filter(
            library = library,
            state = NovelFilterState(unread = 3),
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
        val result = NovelLibraryFilter.filter(
            library = library,
            state = NovelFilterState(unread = 4),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
        )
        assertEquals(listOf(2L), result.flatIds())
    }

    @Test
    fun `bookmarked include keeps only novels with bookmarks`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(item(1, bookmarkCount = 3), item(2, bookmarkCount = 0)),
        )
        val result = NovelLibraryFilter.filter(
            library = library,
            state = NovelFilterState(bookmarked = STATE_INCLUDE),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
        )
        assertEquals(listOf(1L), result.flatIds())
    }

    @Test
    fun `bookmarked exclude keeps only novels without bookmarks`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(item(1, bookmarkCount = 3), item(2, bookmarkCount = 0)),
        )
        val result = NovelLibraryFilter.filter(
            library = library,
            state = NovelFilterState(bookmarked = STATE_EXCLUDE),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
        )
        assertEquals(listOf(2L), result.flatIds())
    }

    @Test
    fun `completed include keeps only completed status novels`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(
                item(1, status = NovelStatusCode.COMPLETED),
                item(2, status = NovelStatusCode.ONGOING),
            ),
        )
        val result = NovelLibraryFilter.filter(
            library = library,
            state = NovelFilterState(completed = STATE_INCLUDE),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
        )
        assertEquals(listOf(1L), result.flatIds())
    }

    @Test
    fun `completed exclude keeps only non-completed novels`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(
                item(1, status = NovelStatusCode.COMPLETED),
                item(2, status = NovelStatusCode.ONGOING),
            ),
        )
        val result = NovelLibraryFilter.filter(
            library = library,
            state = NovelFilterState(completed = STATE_EXCLUDE),
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
        val result = NovelLibraryFilter.filter(
            library = library,
            state = NovelFilterState(downloaded = STATE_INCLUDE),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 99 }, // shouldn't be consulted; downloadCount field wins
            getTracks = { emptyList() },
        )
        assertEquals(listOf(1L), result.flatIds())
    }

    @Test
    fun `downloaded include falls back to lookup when downloadCount is unknown`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(item(1, downloadCount = -1L), item(2, downloadCount = -1L)),
        )
        val result = NovelLibraryFilter.filter(
            library = library,
            state = NovelFilterState(downloaded = STATE_INCLUDE),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { novel -> if (novel.id == 1L) 5 else 0 },
            getTracks = { emptyList() },
        )
        assertEquals(listOf(1L), result.flatIds())
    }

    @Test
    fun `downloaded exclude keeps only un-downloaded novels`() = runBlocking {
        val library = libraryOf(
            "Default" to listOf(item(1, downloadCount = 3L), item(2, downloadCount = 0L)),
        )
        val result = NovelLibraryFilter.filter(
            library = library,
            state = NovelFilterState(downloaded = STATE_EXCLUDE),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
        )
        assertEquals(listOf(2L), result.flatIds())
    }

    @Test
    fun `tracker include drops novels that have no tracks`() = runBlocking {
        val library = libraryOf("Default" to listOf(item(1), item(2)))
        val result = NovelLibraryFilter.filter(
            library = library,
            state = NovelFilterState(tracked = STATE_INCLUDE),
            loggedServiceNames = mapOf(1L to "AniList"),
            getDownloadCount = { 0 },
            getTracks = { novelId -> if (novelId == 1L) listOf(trackOn(1L)) else emptyList() },
        )
        assertEquals(listOf(1L), result.flatIds())
    }

    @Test
    fun `tracker exclude drops novels that have any track`() = runBlocking {
        val library = libraryOf("Default" to listOf(item(1), item(2)))
        val result = NovelLibraryFilter.filter(
            library = library,
            state = NovelFilterState(tracked = STATE_EXCLUDE),
            loggedServiceNames = mapOf(1L to "AniList"),
            getDownloadCount = { 0 },
            getTracks = { novelId -> if (novelId == 1L) listOf(trackOn(1L)) else emptyList() },
        )
        assertEquals(listOf(2L), result.flatIds())
    }

    @Test
    fun `tracker include with specific service drops novels not tracked on that service`() = runBlocking {
        val library = libraryOf("Default" to listOf(item(1), item(2)))
        val result = NovelLibraryFilter.filter(
            library = library,
            state = NovelFilterState(tracked = STATE_INCLUDE, tracker = "MyAnimeList"),
            loggedServiceNames = mapOf(1L to "AniList", 2L to "MyAnimeList"),
            getDownloadCount = { 0 },
            getTracks = { novelId ->
                when (novelId) {
                    1L -> listOf(trackOn(2L))
                    2L -> listOf(trackOn(1L))
                    else -> emptyList()
                }
            },
        )
        assertEquals(listOf(1L), result.flatIds())
    }

    @Test
    fun `tracker exclude with specific service drops novels tracked on that service`() = runBlocking {
        val library = libraryOf("Default" to listOf(item(1), item(2)))
        val result = NovelLibraryFilter.filter(
            library = library,
            state = NovelFilterState(tracked = STATE_EXCLUDE, tracker = "MyAnimeList"),
            loggedServiceNames = mapOf(1L to "AniList", 2L to "MyAnimeList"),
            getDownloadCount = { 0 },
            getTracks = { novelId ->
                when (novelId) {
                    1L -> listOf(trackOn(2L))
                    2L -> listOf(trackOn(1L))
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
        val result = NovelLibraryFilter.filter(
            library = library,
            state = NovelFilterState(unread = STATE_INCLUDE),
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
        val result = NovelLibraryFilter.filter(
            library = library,
            state = NovelFilterState(unread = STATE_INCLUDE, bookmarked = STATE_INCLUDE),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
        )
        assertEquals(listOf(1L), result.flatIds())
    }

    @Test
    fun `isAnyActive is false on the default state`() {
        assertEquals(false, NovelFilterState().isAnyActive)
    }

    @Test
    fun `isAnyActive is true when any filter is set`() {
        assertEquals(true, NovelFilterState(unread = STATE_INCLUDE).isAnyActive)
        assertEquals(true, NovelFilterState(bookmarked = STATE_EXCLUDE).isAnyActive)
    }

    @Test
    fun `keepEmptyCategories preserves categories whose items were entirely filtered out`() = runBlocking {
        val library = libraryOf(
            "Reading" to listOf(item(1, unread = 5)),
            "Completed" to listOf(item(2, unread = 0)),
        )
        val result = NovelLibraryFilter.filter(
            library = library,
            state = NovelFilterState(unread = STATE_INCLUDE),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
            keepEmptyCategories = true,
        )
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
        val result = NovelLibraryFilter.filter(
            library = library,
            state = NovelFilterState(unread = STATE_INCLUDE),
            loggedServiceNames = emptyMap(),
            getDownloadCount = { 0 },
            getTracks = { emptyList() },
        )
        assertEquals(1, result.size)
        assertEquals(listOf(1L), result.flatIds())
    }

    // --- helpers ----------------------------------------------------------------------------

    private fun Map<NovelCategory, List<LibraryItem.Novel>>.flatIds(): List<Long> =
        values.flatten().mapNotNull { it.libraryNovel.novel.id }.sorted()

    private fun libraryOf(
        vararg pairs: Pair<String, List<LibraryItem.Novel>>,
    ): Map<NovelCategory, List<LibraryItem.Novel>> = pairs.associate { (name, items) ->
        NovelCategoryImpl().apply {
            id = name.hashCode()
            this.name = name
            order = 0
        } to items
    }

    private fun item(
        novelId: Long,
        unread: Int = 0,
        read: Int = 0,
        bookmarkCount: Int = 0,
        status: Int = NovelStatusCode.UNKNOWN,
        source: String = "test-source",
        downloadCount: Long = -1L,
    ): LibraryItem.Novel {
        val novel = Novel(
            id = novelId,
            source = source,
            url = "/n/$novelId",
            title = "title-$novelId",
            author = null,
            artist = null,
            description = null,
            genres = null,
            status = status,
            thumbnailUrl = null,
            favorite = true,
            lastUpdate = 0L,
            initialized = true,
            chapterFlags = 0,
            dateAdded = 0L,
            updateStrategy = 0,
            coverLastModified = 0L,
        )
        return LibraryItem.Novel(
            libraryNovel = LibraryNovel(
                novel = novel,
                unread = unread,
                read = read,
                bookmarkCount = bookmarkCount,
            ),
            downloadCount = downloadCount,
        )
    }

    private fun trackOn(serviceId: Long): NovelTrack = NovelTrack(
        novelId = 0L,
        syncId = serviceId,
        remoteId = 0L,
        title = "",
    )
}
