package eu.kanade.tachiyomi.data.track.novelupdates

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.assertions.throwables.shouldThrowExactly
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
import java.io.IOException

/**
 * A NovelUpdates push against a fake site. Source A (chapters 1 to 4) and source B (1 to 10) are one
 * merge group with tracker sharing off, so A's track hears only A.
 */
class NovelUpdatesPushTest {

    private lateinit var appScope: InjektScope

    private val askedFor = mutableListOf<String>()

    /** The note read answers 400, as the site did on device for a series being bound. */
    private var notesRefused = false

    /** The note read succeeds but answers something that is not a note. */
    private var notesUnreadable = false

    private fun chapter(novelId: Long, number: Int) = NovelChapter(
        id = novelId * 1000 + number, novelId = novelId, url = "", name = "", read = true, bookmark = false,
        lastTextProgress = 0L, chapterNumber = number.toDouble(), sourceOrder = number.toLong(), dateFetch = 0L,
        dateUpload = 0L, page = "",
    )

    private fun releaseRow(number: Int) =
        """<li class="sp_li_chp"><a href="//www.novelupdates.com/group/g/"></a>""" +
            """<a href="//www.novelupdates.com/extnu/${10 + number}/">Chapter $number</a></li>"""

    // The note reads progress 5 (plus WordPress's trailing 0), chapters 6 and 7 are posted, every write lands.
    private val site = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val form = request.body as? FormBody
        val action = form?.let { f -> (0 until f.size).firstOrNull { f.name(it) == "action" }?.let(f::value) }
        askedFor += action ?: request.url.encodedPath
        val refused = action == "wi_notestagsfic" && notesRefused
        val body = when (action) {
            "wi_notestagsfic" -> if (notesUnreadable) "<html>not a note</html>" else NOTE
            "nd_getchapters" -> "<ol>${releaseRow(6)}${releaseRow(7)}</ol>"
            else -> ""
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(if (refused) 400 else 200).message(if (refused) "Bad Request" else "OK")
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

    @Test
    fun `a run of reads asks the site for the series' releases once`() = runTest {
        val tracker = NovelUpdates(TrackerManager.NOVELUPDATES)

        tracker.update(track(6.0), didReadChapter = true)
        tracker.update(track(7.0), didReadChapter = true)

        askedFor.count { it == "nd_getchapters" } shouldBe 1
    }

    @Test
    fun `a note the site refuses to read leaves the series where it was`() = runTest {
        notesRefused = true

        runCatching { NovelUpdates(TrackerManager.NOVELUPDATES).update(track(6.0), didReadChapter = false) }

        askedFor.none { it == "/updatelist.php" } shouldBe true
    }

    @Test
    fun `a note that does not parse leaves the series where it was`() = runTest {
        notesUnreadable = true

        runCatching { NovelUpdates(TrackerManager.NOVELUPDATES).update(track(6.0), didReadChapter = false) }

        askedFor.none { it == "/updatelist.php" } shouldBe true
    }

    /** Counted as done, the update would never be retried, and the site would keep its old progress. */
    @Test
    fun `a note that does not parse fails the update`() = runTest {
        notesUnreadable = true

        shouldThrowExactly<IOException> {
            NovelUpdates(TrackerManager.NOVELUPDATES).update(track(6.0), didReadChapter = false)
        }
    }

    private companion object {
        const val SOURCE_A = 1L
        const val SOURCE_B = 2L
        const val NOTE = """{"notes":"total chapters read: 5","tags":""}0"""
    }
}
