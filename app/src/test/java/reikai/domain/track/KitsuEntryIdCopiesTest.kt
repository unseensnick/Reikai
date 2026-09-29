package reikai.domain.track

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelTrackRepository
import reikai.domain.novel.interactor.UpsertNovelTrack
import reikai.domain.novel.model.NovelTrack
import tachiyomi.domain.track.interactor.UpsertTrack
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository

/** Healing a Kitsu entry id reaches every stored copy of the bad record, in both track tables, and nothing else. */
class KitsuEntryIdCopiesTest {

    private val mangaRows = MutableStateFlow(
        listOf(
            // Six members of one merged series, all carrying the same restored row.
            mangaTrack(id = 1L, mangaId = 10L, remoteId = ENTRY, libraryId = 0L),
            mangaTrack(id = 2L, mangaId = 11L, remoteId = ENTRY, libraryId = null),
            // A row already bound properly, which happens to share the number.
            mangaTrack(id = 3L, mangaId = 12L, remoteId = ENTRY, libraryId = 555L),
            // Another tracker's row with the same number means something else entirely.
            mangaTrack(id = 4L, mangaId = 10L, remoteId = ENTRY, libraryId = 0L, trackerId = OTHER_TRACKER),
        ),
    )
    private val novelRows = MutableStateFlow(
        listOf(novelTrack(id = 7L, novelId = 20L, remoteId = ENTRY, libraryId = 0L)),
    )

    private val trackRepository = object : TrackRepository {
        override suspend fun getTrackById(id: Long) = mangaRows.value.find { it.id == id }
        override suspend fun getTracksByMangaId(mangaId: Long) = mangaRows.value.filter { it.mangaId == mangaId }
        override fun getTracksAsFlow(): Flow<List<Track>> = mangaRows
        override fun getTracksByMangaIdAsFlow(mangaId: Long) = mangaRows.map { rows ->
            rows.filter {
                it.mangaId ==
                    mangaId
            }
        }
        override suspend fun delete(mangaId: Long, trackerId: Long) = Unit
        override suspend fun upsert(track: Track) = upsertAll(listOf(track))
        override suspend fun upsertAll(tracks: List<Track>) {
            val byKey = tracks.associateBy { it.mangaId to it.trackerId }
            mangaRows.value = mangaRows.value.map { byKey[it.mangaId to it.trackerId] ?: it }
        }
    }

    private val novelRepository = object : NovelTrackRepository {
        override suspend fun getTrackById(id: Long) = novelRows.value.find { it.id == id }
        override suspend fun getTracksByNovelId(novelId: Long) = novelRows.value.filter { it.novelId == novelId }
        override fun getTracksByNovelIdAsFlow(novelId: Long) = novelRows.map { rows ->
            rows.filter {
                it.novelId ==
                    novelId
            }
        }
        override fun getTracksAsFlow(): Flow<List<NovelTrack>> = novelRows
        override suspend fun delete(novelId: Long, trackerId: Long) = Unit
        override suspend fun upsert(track: NovelTrack) = upsertAll(listOf(track))
        override suspend fun upsertAll(tracks: List<NovelTrack>): Boolean {
            val byKey = tracks.associateBy { it.novelId to it.trackerId }
            novelRows.value = novelRows.value.map { byKey[it.novelId to it.trackerId] ?: it }
            return true
        }
    }

    private val copies = KitsuEntryIdCopies(
        trackRepository,
        UpsertTrack(trackRepository),
        novelRepository,
        UpsertNovelTrack(novelRepository),
    )

    @Test
    fun `every manga copy of the bad record moves to the manga and entry ids`() = runTest {
        copies.heal(KITSU, entryId = ENTRY, mangaId = MANGA)

        mangaRows.value.map { Triple(it.id, it.remoteId, it.libraryId) } shouldContainExactlyInAnyOrder listOf(
            Triple(1L, MANGA, ENTRY),
            Triple(2L, MANGA, ENTRY),
            Triple(3L, ENTRY, 555L),
            Triple(4L, ENTRY, 0L),
        )
    }

    @Test
    fun `a novel copy of the bad record moves too`() = runTest {
        copies.heal(KITSU, entryId = ENTRY, mangaId = MANGA)

        novelRows.value.single().let { it.remoteId to it.libraryId } shouldBe (MANGA to ENTRY)
    }

    private fun mangaTrack(id: Long, mangaId: Long, remoteId: Long, libraryId: Long?, trackerId: Long = KITSU) =
        Track(
            id = id,
            mangaId = mangaId,
            trackerId = trackerId,
            remoteId = remoteId,
            libraryId = libraryId,
            title = "Solo Leveling",
            lastChapterRead = 0.0,
            totalChapters = 0L,
            status = 1L,
            score = 0.0,
            remoteUrl = "",
            startDate = 0L,
            finishDate = 0L,
            private = false,
        )

    private fun novelTrack(id: Long, novelId: Long, remoteId: Long, libraryId: Long?) = NovelTrack(
        id = id,
        novelId = novelId,
        trackerId = KITSU,
        remoteId = remoteId,
        libraryId = libraryId,
        title = "Solo Leveling",
        lastChapterRead = 0.0,
        totalChapters = 0L,
        status = 1L,
        score = 0.0,
        remoteUrl = "",
        startDate = 0L,
        finishDate = 0L,
        private = false,
    )

    private companion object {
        const val KITSU = 3L
        const val OTHER_TRACKER = 2L
        const val ENTRY = 107289511L
        const val MANGA = 54114L
    }
}
