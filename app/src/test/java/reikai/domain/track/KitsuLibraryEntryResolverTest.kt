package reikai.domain.track

import eu.kanade.tachiyomi.data.database.models.Track
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The Kitsu entry-id heal over a stand-in remote: the signed-in user's library, keyed by manga id, and the
 * library entries Kitsu would return by entry id. Ids are the real ones a Yokai restore left behind.
 */
class KitsuLibraryEntryResolverTest {

    private val user = "1697368"
    private val soloLeveling = 54114L
    private val soloLevelingEntry = 107289511L
    private val strangersEntry = 99000001L

    // The user's Kitsu library: manga id to library entry id.
    private val library = mapOf(soloLeveling to soloLevelingEntry)
    private val entries = mapOf(
        soloLevelingEntry to KitsuEntryLookup(ownerId = user, viewerId = user, mangaId = soloLeveling),
        strangersEntry to KitsuEntryLookup(ownerId = "42", viewerId = user, mangaId = 777L),
    )

    private val entryLookups = mutableListOf<Long>()
    private val rewrites = mutableListOf<Pair<Long, Long>>()

    private val resolver = KitsuLibraryEntryResolver(
        findInLibrary = { track ->
            library[track.remote_id]?.let { entryId ->
                Track.create(KITSU).also {
                    it.remote_id = track.remote_id
                    it.library_id = entryId
                }
            }
        },
        findEntry = { entryId ->
            entryLookups += entryId
            entries[entryId]
        },
        rewriteCopies = { entryId, mangaId -> rewrites += entryId to mangaId },
    )

    private fun row(remoteId: Long, libraryId: Long?) = Track.create(KITSU).also {
        it.manga_id = 1L
        it.remote_id = remoteId
        it.library_id = libraryId
    }

    @Test
    fun `a row holding the user's entry id heals to the manga and entry ids`() = runTest {
        val track = row(remoteId = soloLevelingEntry, libraryId = 0L)

        resolver.find(track)

        (track.remote_id to track.library_id) shouldBe (soloLeveling to soloLevelingEntry)
    }

    @Test
    fun `a healed row is found in the library`() = runTest {
        resolver.find(row(remoteId = soloLevelingEntry, libraryId = 0L)) shouldNotBe null
    }

    @Test
    fun `a row with a null library id heals too`() = runTest {
        val track = row(remoteId = soloLevelingEntry, libraryId = null)

        resolver.find(track)

        track.remote_id shouldBe soloLeveling
    }

    @Test
    fun `the heal rewrites every stored copy of the bad record`() = runTest {
        resolver.find(row(remoteId = soloLevelingEntry, libraryId = 0L))

        rewrites shouldBe listOf(soloLevelingEntry to soloLeveling)
    }

    @Test
    fun `an entry owned by someone else leaves the row unchanged`() = runTest {
        val track = row(remoteId = strangersEntry, libraryId = 0L)

        resolver.find(track)

        (track.remote_id to track.library_id) shouldBe (strangersEntry to 0L)
    }

    @Test
    fun `an entry owned by someone else rewrites nothing`() = runTest {
        resolver.find(row(remoteId = strangersEntry, libraryId = 0L))

        rewrites shouldBe emptyList()
    }

    @Test
    fun `an entry that is not a manga leaves the row unchanged`() = runTest {
        val anime = 55500001L
        val resolver = KitsuLibraryEntryResolver(
            findInLibrary = { null },
            findEntry = { KitsuEntryLookup(ownerId = user, viewerId = user, mangaId = null) },
            rewriteCopies = { entryId, mangaId -> rewrites += entryId to mangaId },
        )
        val track = row(remoteId = anime, libraryId = 0L)

        resolver.find(track)

        track.remote_id shouldBe anime
    }

    @Test
    fun `a row with its library id never looks up an entry`() = runTest {
        resolver.find(row(remoteId = soloLevelingEntry, libraryId = 555L))

        entryLookups shouldBe emptyList()
    }

    @Test
    fun `a row whose manga is in the library never looks up an entry`() = runTest {
        resolver.find(row(remoteId = soloLeveling, libraryId = 0L))

        entryLookups shouldBe emptyList()
    }

    @Test
    fun `a missing library id resolves to the healed entry id for a write`() = runTest {
        resolver.libraryId(row(remoteId = soloLevelingEntry, libraryId = 0L)) shouldBe soloLevelingEntry
    }

    @Test
    fun `a stored library id is written to as is`() = runTest {
        resolver.libraryId(row(remoteId = soloLeveling, libraryId = soloLevelingEntry)) shouldBe soloLevelingEntry
    }

    private companion object {
        const val KITSU = 3L
    }
}
