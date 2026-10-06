package eu.kanade.tachiyomi.data.track.novelupdates

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.data.track.installTrackerTestGraph
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.model.NovelChapter
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope

/**
 * A NovelUpdates push against a fake site. Source A (chapters 1 to 4) and source B (1 to 10) are one
 * merge group with tracker sharing off, so A's track hears only A.
 */
class NovelUpdatesPushTest {

    private lateinit var appScope: InjektScope

    private fun chapter(novelId: Long, number: Int) = NovelChapter(
        id = novelId * 1000 + number, novelId = novelId, url = "", name = "", read = true, bookmark = false,
        lastTextProgress = 0L, chapterNumber = number.toDouble(), sourceOrder = number.toLong(), dateFetch = 0L,
        dateUpload = 0L, page = "",
    )

    // The note reads progress 5 (plus WordPress's trailing 0) and every write lands.
    private val site = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val form = request.body as? FormBody
        val action = form?.let { f -> (0 until f.size).firstOrNull { f.name(it) == "action" }?.let(f::value) }
        val body = if (action == "wi_notestagsfic") """{"notes":"total chapters read: 5","tags":""}0""" else ""
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody("text/html".toMediaType())).build()
    }.build()

    private fun track(progress: Double) = Track.create(TrackerManager.NOVELUPDATES).apply {
        manga_id = SOURCE_A
        remote_id = 99L
        title = "Series"
        status = NovelUpdates.READING
        last_chapter_read = progress
    }

    @BeforeEach
    fun setUp() {
        val mergeManager = mockk<NovelMergeManager> {
            every { relatedIdsChanges() } returns emptyFlow()
            coEvery { computeRelatedIds(SOURCE_A) } returns longArrayOf(SOURCE_A, SOURCE_B)
            coEvery { relatedIdsList(SOURCE_A) } returns listOf(SOURCE_A, SOURCE_B)
        }
        val chapters = mockk<NovelChapterRepository> {
            coEvery { getByNovelId(SOURCE_A) } returns (1..4).map { chapter(SOURCE_A, it) }
            coEvery { getByNovelId(SOURCE_B) } returns (1..10).map { chapter(SOURCE_B, it) }
        }
        val sharingOff = ReikaiLibraryPreferences(
            InMemoryPreferenceStore(sequenceOf(InMemoryPreference("sync_tracker_links_grouped", false, true))),
        )
        appScope = installTrackerTestGraph(
            network = mockk<NetworkHelper>(relaxed = true) { every { client } returns site },
            preferences = InMemoryPreferenceStore(
                sequenceOf(InMemoryPreference("novelupdates_unread_push", true, false)),
            ),
        ) {
            every { novelChapterRepository } returns chapters
            every { novelMergeManager } returns mergeManager
            every { getNovelTracks } returns GetNovelTracks(mockk(), mergeManager, sharingOff)
        }
    }

    @AfterEach
    fun tearDown() {
        Injekt = appScope
    }

    @Test
    fun `with sharing off an unread moves the site to the bound source's own progress`() = runTest {
        val pushed = NovelUpdates(TrackerManager.NOVELUPDATES).pushUnread(track(5.0), listOf(chapter(SOURCE_A, 5)))

        pushed?.last_chapter_read shouldBe 4.0
    }

    private companion object {
        const val SOURCE_A = 1L
        const val SOURCE_B = 2L
    }
}
