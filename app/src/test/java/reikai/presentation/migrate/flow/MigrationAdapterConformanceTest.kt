package reikai.presentation.migrate.flow

import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import mihon.domain.source.interactor.UpdateMangaFromRemote
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.data.novel.NovelChapterSyncRules
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.chapter.NoChapterNumberOverrides
import reikai.domain.chapter.NoChapterTombstones
import reikai.domain.entry.EntryId
import reikai.domain.novel.LnSourceIdentity
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.source.CatalogueEnd
import reikai.novel.host.ChapterItem
import reikai.novel.host.NovelItem
import reikai.novel.host.SourceNovel
import reikai.novel.source.NovelItemsPage
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.recents.EmittingPreferenceStore
import reikai.util.testAppScope
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.GetChapterSummaryByMangaId
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterSummary
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import java.io.IOException
import kotlin.time.Instant

/**
 * The rules both migration adapters must answer alike, run over each real adapter with its engine
 * mocked at the repository and source boundary. The world is the same for both: one stored entry
 * (id [ENTRY_ID], url [OWN_URL]) on an uninstalled source last seen as [OLD_NAME], and an installed
 * target source listing the given urls, with one row ([PICK_ID]) already stored from it. Every stored
 * row has the given number of chapters until the source is asked for a chapter list to store, and
 * the given chapter urls, as the source lists them, after that.
 */
class MigrationAdapterConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a search never offers the entry's own listing on its own source`(probe: Probe) = runTest {
        val adapter = probe.adapter(listing = listOf(OWN_URL, "/other"))

        val keys = adapter.candidates(probe.entry(onSource = probe.target), "q", probe.target).map { it.key }

        keys shouldBe listOf("${probe.target}:/other")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a suggestion is never the entry's own listing on its own source`(probe: Probe) = runTest {
        val adapter = probe.adapter(listing = listOf(OWN_URL))

        adapter.suggest(probe.entry(onSource = probe.target), probe.target, MigrationTuning()) shouldBe null
    }

    // The source's whole answer is scored with the own listing still in it. Dropping it first would leave
    // one hit, which the search engine accepts without scoring, so an unrelated title would be suggested.
    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a suggestion is scored before the own listing is dropped`(probe: Probe) = runTest {
        val adapter = probe.adapter(listing = listOf(OWN_URL, "/other"))

        adapter.suggest(probe.entry(onSource = probe.target), probe.target, MigrationTuning()) shouldBe null
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `resolving a target that already has chapters is not a fresh sync`(probe: Probe) = runTest {
        val adapter = probe.adapter(listing = listOf("/t"), chapters = 3)
        val hit = adapter.candidates(probe.entry(onSource = probe.oldSource), "q", probe.target).single()

        adapter.resolve(hit)?.syncedNow shouldBe false
    }

    // The engine skips its own refresh after a fresh sync, so one that stored nothing must not count.
    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a fetch that stored no chapters is not a fresh sync`(probe: Probe) = runTest {
        val adapter = probe.adapter(listing = listOf("/t"), chapters = 0, sourceChapters = emptyList())
        val hit = adapter.candidates(probe.entry(onSource = probe.oldSource), "q", probe.target).single()

        adapter.resolve(hit)?.syncedNow shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a stored target without chapters counts as unknown`(probe: Probe) = runTest {
        probe.adapter(chapters = 0).storedCandidate(ENTRY_ID)?.chapterCount shouldBe null
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `resolving a stored pick that has no chapters asks its source for them`(probe: Probe) = runTest {
        val adapter = probe.adapter(chapters = 0, sourceChapters = listOf("/c1", "/c2"))
        val pick = adapter.storedCandidate(PICK_ID) ?: error("the picked row is stored")

        adapter.resolve(pick)?.candidate?.chapterCount shouldBe 2
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a peek counts a chapter the source lists twice once`(probe: Probe) = runTest {
        val adapter = probe.adapter(listing = listOf("/t"), chapters = 0, sourceChapters = listOf("/c1", "/c1", "/c2"))
        val hit = adapter.candidates(probe.entry(onSource = probe.oldSource), "q", probe.target).single()

        adapter.peekCounts(hit)?.chapterCount shouldBe 2
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a peek takes the number the source gives a chapter over its name`(probe: Probe) = runTest {
        val adapter = probe.adapter(listing = listOf("/t"), chapters = 0, sourceChapters = listOf("/c1", NUMBERED_URL))
        val hit = adapter.candidates(probe.entry(onSource = probe.oldSource), "q", probe.target).single()

        adapter.peekCounts(hit)?.latestChapter shouldBe SOURCE_NUMBER
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an entry from an uninstalled source is still named`(probe: Probe) = runTest {
        probe.adapter().loadEntries(listOf(ENTRY_ID)).single().sourceName shouldBe OLD_NAME
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an entry shows its stored chapter count and highest number`(probe: Probe) = runTest {
        val entry = probe.adapter(chapters = 3).loadEntries(listOf(ENTRY_ID)).single()

        (entry.chapterCount to entry.latestChapter) shouldBe (3 to 3.0)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an entry whose chapters carry no recognized number shows no latest chapter`(probe: Probe) = runTest {
        val entry = probe.adapter(chapters = 2, unnumbered = true).loadEntries(listOf(ENTRY_ID)).single()

        entry.latestChapter shouldBe null
    }

    companion object {
        const val ENTRY_ID = 1L
        const val PICK_ID = 2L
        const val OWN_URL = "/own"
        const val OLD_NAME = "Old source"

        // The source numbers this chapter itself, whatever its name says: a manga source in its
        // prepare hook, the last place its contract can fix a listed chapter, a novel source as it lists it.
        const val NUMBERED_URL = "/extra"
        const val SOURCE_NUMBER = 42.0

        @JvmStatic
        fun probes() = listOf(MangaProbe, NovelProbe)

        /** The number the stored chapter at [index] carries; -1 is the recognizer finding none. */
        fun storedNumber(index: Int, unnumbered: Boolean) = if (unnumbered) -1.0 else index + 1.0

        /** What the database counts over [count] stored chapters numbered by [storedNumber]. */
        fun storedSummary(count: Int, unnumbered: Boolean) = ChapterSummary(
            chapterCount = count.toLong(),
            latestChapterNumber = (0..<count).maxOfOrNull { storedNumber(it, unnumbered) },
        )
    }
}

/** One content type's adapter over the shared world [MigrationAdapterConformanceTest] describes. */
sealed interface Probe {
    val oldSource: String
    val target: String

    fun adapter(
        listing: List<String> = emptyList(),
        chapters: Int = 1,
        sourceChapters: List<String> = emptyList(),
        unnumbered: Boolean = false,
    ): MigrationFlowAdapter

    /** The stored entry as the flow loads it, placed on [onSource]. */
    fun entry(onSource: String): MigrationEntry
}

object MangaProbe : Probe {
    override val oldSource = "1"
    override val target = "2"

    override fun toString() = "manga"

    private val stored = Manga.create().copy(
        id = MigrationAdapterConformanceTest.ENTRY_ID,
        source = 1L,
        url = MigrationAdapterConformanceTest.OWN_URL,
        title = "Title",
    )
    private val picked = Manga.create().copy(
        id = MigrationAdapterConformanceTest.PICK_ID,
        source = 2L,
        url = "/picked",
        title = "Picked",
        thumbnailUrl = "cover",
    )

    override fun adapter(
        listing: List<String>,
        chapters: Int,
        sourceChapters: List<String>,
        unnumbered: Boolean,
    ): MigrationFlowAdapter {
        val rows = mutableMapOf(stored.id to stored, picked.id to picked)
        var synced = false
        val catalogue = mockk<HttpSource>(relaxed = true) {
            every { id } returns 2L
            every { name } returns "Target"
            every { getFilterList() } returns FilterList()
            coEvery { getSearchManga(any(), any(), any()) } returns MangasPage(
                listing.map { url ->
                    SManga.create().apply {
                        this.url = url
                        title = url
                    }
                },
                hasNextPage = false,
            )
            coEvery { getMangaUpdate(any(), any(), any(), any()) } answers {
                SMangaUpdate(
                    firstArg(),
                    sourceChapters.map { url ->
                        SChapter.create().apply {
                            this.url = url
                            name = "Chapter ${url.drop(2)}"
                        }
                    },
                )
            }
            @Suppress("DEPRECATION")
            every { prepareNewChapter(any(), any()) } answers {
                val chapter = firstArg<SChapter>()
                if (chapter.url == MigrationAdapterConformanceTest.NUMBERED_URL) {
                    chapter.chapter_number = MigrationAdapterConformanceTest.SOURCE_NUMBER.toFloat()
                }
            }
        }
        return MangaMigrationFlowAdapter(
            sourceManager = mockk<SourceManager> {
                coEvery { get(1L) } returns null
                coEvery { getOrStub(1L) } returns StubSource(1L, "en", MigrationAdapterConformanceTest.OLD_NAME)
                coEvery { get(2L) } returns catalogue
                coEvery { getOrStub(2L) } returns catalogue
            },
            sourcePreferences = mockk(relaxed = true),
            getManga = mockk<GetManga> { coEvery { await(any<Long>()) } answers { rows[firstArg()] } },
            getChaptersByMangaId = mockk<GetChaptersByMangaId> {
                coEvery { await(any(), any()) } answers {
                    List(if (synced) sourceChapters.size else chapters) {
                        Chapter.create().copy(
                            id = it + 1L,
                            chapterNumber = MigrationAdapterConformanceTest.storedNumber(it, unnumbered),
                        )
                    }
                }
            },
            getChapterSummaryByMangaId = mockk<GetChapterSummaryByMangaId> {
                coEvery { await(any()) } answers {
                    MigrationAdapterConformanceTest.storedSummary(
                        if (synced) sourceChapters.size else chapters,
                        unnumbered,
                    )
                }
            },
            networkToLocalManga = mockk<NetworkToLocalManga> {
                coEvery { this@mockk.invoke(any<List<Manga>>()) } answers {
                    firstArg<List<Manga>>().mapIndexed { i, manga ->
                        manga.copy(id = 100L + i, thumbnailUrl = "cover").also { rows[it.id] = it }
                    }
                }
            },
            coverCache = mockk(relaxed = true),
            downloadManager = mockk(relaxed = true),
            migrateManga = mockk(relaxed = true),
            mergeManager = mockk(relaxed = true),
            getFavorites = mockk(relaxed = true),
            updateMangaFromRemote = mockk<UpdateMangaFromRemote>(relaxed = true) {
                coEvery { this@mockk.invoke(any<Manga>(), any(), fetchChapters = true, any(), any()) } answers {
                    synced = true
                    Result.failure(IOException("the probe stores no update"))
                }
            },
        )
    }

    override fun entry(onSource: String) = MigrationEntry(
        id = EntryId.Manga(stored.id),
        title = stored.title,
        sourceKey = onSource,
        sourceName = "",
        chapterCount = 1,
        cover = null,
        payload = MigrationPayload.OfManga(stored.copy(source = onSource.toLong())),
    )
}

object NovelProbe : Probe {
    override val oldSource = "old"
    override val target = "target"

    override fun toString() = "novels"

    private val stored = Novel.create().copy(
        id = MigrationAdapterConformanceTest.ENTRY_ID,
        source = "old",
        url = MigrationAdapterConformanceTest.OWN_URL,
        title = "Title",
    )
    private val picked = Novel.create().copy(
        id = MigrationAdapterConformanceTest.PICK_ID,
        source = "target",
        url = "/picked",
        title = "Picked",
    )

    override fun adapter(
        listing: List<String>,
        chapters: Int,
        sourceChapters: List<String>,
        unnumbered: Boolean,
    ): MigrationFlowAdapter {
        val rows = mutableMapOf(stored.id to stored, picked.id to picked)
        var synced = false
        val targetSource = mockk<NovelSource>(relaxed = true) {
            every { id } returns "target"
            every { name } returns "Target"
            coEvery { search(any(), any(), any()) } returns
                NovelItemsPage(listing.map { NovelItem(it, it, null) }, CatalogueEnd.Reported(false))
            coEvery { parseNovel(any()) } answers {
                synced = true
                SourceNovel(
                    firstArg(),
                    chapters = sourceChapters.map {
                        ChapterItem("Chapter ${it.drop(2)}", it, chapterNumber = numberOf(it))
                    },
                )
            }
        }
        val sourceManager = NovelSourceManager(
            scope = testAppScope(),
            installer = { mockk(relaxed = true) },
            extensionManager = mockk<ExtensionManager> {
                every { loadedNovelExtensionsFlow } returns MutableStateFlow(emptyList())
            },
            prefs = NovelPreferences(EmittingPreferenceStore()).apply {
                seenNovelSources().set(mapOf("old" to LnSourceIdentity(MigrationAdapterConformanceTest.OLD_NAME)))
            },
        ).also { it.register(targetSource) }
        return NovelMigrationFlowAdapter(
            sourceManager = sourceManager,
            getEnabledNovelSources = mockk(),
            sourcePreferences = mockk(),
            novelPreferences = mockk(),
            novelRepository = mockk<NovelRepository> {
                coEvery { getById(any()) } answers { rows[firstArg()] }
                coEvery { getByUrlAndSource(any(), any()) } answers {
                    rows.values.firstOrNull { it.url == firstArg() && it.source == secondArg() }
                }
                coEvery { insertOrGet(any()) } answers {
                    firstArg<Novel>().copy(id = 100L + rows.size).also { rows[it.id] = it }
                }
            },
            chapterRepository = mockk<NovelChapterRepository> {
                coEvery { getByNovelId(any()) } answers {
                    List(if (synced) sourceChapters.size else chapters) {
                        storedChapter(firstArg(), MigrationAdapterConformanceTest.storedNumber(it, unnumbered))
                    }
                }
                coEvery { getChapterSummaryByNovelId(any()) } answers {
                    MigrationAdapterConformanceTest.storedSummary(
                        if (synced) sourceChapters.size else chapters,
                        unnumbered,
                    )
                }
            },
            // Real, not relaxed: a relaxed Preference cannot answer get() with a Boolean.
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            coverCache = mockk(relaxed = true),
            downloadManagerProvider = { mockk(relaxed = true) },
            migrateNovel = mockk(),
            mergeManager = mockk(),
            installer = mockk(relaxed = true),
            syncRules = NovelChapterSyncRules(
                NoChapterNumberOverrides,
                InMemoryChapterCopyMarks(),
                NoChapterTombstones,
            ),
        )
    }

    private fun numberOf(url: String) =
        MigrationAdapterConformanceTest.SOURCE_NUMBER.takeIf { url == MigrationAdapterConformanceTest.NUMBERED_URL }

    override fun entry(onSource: String) = MigrationEntry(
        id = EntryId.Novel(stored.id),
        title = stored.title,
        sourceKey = onSource,
        sourceName = "",
        chapterCount = 1,
        cover = null,
        payload = MigrationPayload.OfNovel(stored.copy(source = onSource)),
    )

    private fun storedChapter(novelId: Long, number: Double) = NovelChapter(
        id = number.toLong(),
        novelId = novelId,
        url = "/c$number",
        name = "Chapter $number",
        read = false,
        bookmark = false,
        lastTextProgress = 0L,
        chapterNumber = number,
        sourceOrder = 0L,
        dateFetch = Instant.fromEpochMilliseconds(0L),
        dateUpload = null,
        page = "",
    )
}
