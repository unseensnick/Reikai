package reikai.presentation.migrate.flow

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.runTest
import mihon.domain.source.interactor.UpdateMangaFromRemote
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.data.novel.NovelChapterSyncRules
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.chapter.NoChapterNumberOverrides
import reikai.domain.chapter.NoChapterTombstones
import reikai.domain.entry.EntryId
import reikai.domain.entry.GetEntryCustomInfo
import reikai.domain.manga.MangaMergeManager
import reikai.domain.novel.LnSourceIdentity
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetCustomNovelInfo
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.repository.CustomNovelInfoRepository
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
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.CustomMangaInfoRepository
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

    // The list model passes the tuning through untyped, so a toggle dropped here is lost for that type alone.
    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `the search toggles saved for a type read back as saved`(probe: Probe) = runTest {
        val adapter = probe.adapter()
        val tuning = MigrationTuning(
            deepSearch = true,
            prioritizeByChapters = true,
            hideUnmatched = true,
            hideWithoutUpdates = true,
        )

        adapter.persistTuning(tuning)

        adapter.readTuning() shouldBe tuning
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
    fun `with more than one language on, a row names its source's language`(probe: Probe) = runTest {
        probe.withLanguages(setOf("en", "ja")).loadEntries(listOf(ENTRY_ID)).single().sourceName shouldBe
            "$OLD_NAME (EN)"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `with one language on, a row names its source alone`(probe: Probe) = runTest {
        probe.withLanguages(setOf("en")).loadEntries(listOf(ENTRY_ID)).single().sourceName shouldBe OLD_NAME
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

    // The auto search falls back to the series' other titles

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a stored alternative title reaches the fallback search`(probe: Probe) = runTest {
        val adapter = probe.adapter(listing = listOf("/Demon Slayer", "/Unrelated"), altTitles = listOf("Demon Slayer"))
        val entry = adapter.loadEntries(listOf(ENTRY_ID)).single()

        adapter.suggest(entry, probe.target, MigrationTuning())?.key shouldBe "${probe.target}:/Demon Slayer"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a merged sibling's title reaches the fallback search`(probe: Probe) = runTest {
        val adapter = probe.adapter(
            listing = listOf("/Kimetsu no Yaiba", "/Unrelated"),
            siblingTitle = "Kimetsu no Yaiba",
        )
        val entry = adapter.loadEntries(listOf(ENTRY_ID)).single()

        adapter.suggest(entry, probe.target, MigrationTuning())?.key shouldBe "${probe.target}:/Kimetsu no Yaiba"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `with no other titles the same listing suggests nothing`(probe: Probe) = runTest {
        val adapter = probe.adapter(listing = listOf("/Kimetsu no Yaiba", "/Unrelated"))
        val entry = adapter.loadEntries(listOf(ENTRY_ID)).single()

        adapter.suggest(entry, probe.target, MigrationTuning()) shouldBe null
    }

    // Loading reads each row once; the flags reuse what loading read

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an entry with Edit info offers to carry it`(probe: Probe) = runTest {
        val adapter = probe.adapter(altTitles = listOf("Other"))

        adapter.applicableFlags(adapter.loadEntries(listOf(ENTRY_ID))) shouldContain MigrationDataFlag.CUSTOM_INFO
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an entry without Edit info does not offer to carry it`(probe: Probe) = runTest {
        val adapter = probe.adapter()

        adapter.applicableFlags(adapter.loadEntries(listOf(ENTRY_ID))) shouldNotContain MigrationDataFlag.CUSTOM_INFO
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `the flags read no Edit info of their own`(probe: Probe) = runTest {
        val adapter = probe.adapter(altTitles = listOf("Other"))
        val entries = adapter.loadEntries(listOf(ENTRY_ID))
        val loaded = probe.reads.customInfo

        adapter.applicableFlags(entries)

        probe.reads.customInfo shouldBe loaded
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `loading a merged entry reads each member's row once`(probe: Probe) = runTest {
        probe.adapter(siblingTitle = "Sibling").loadEntries(listOf(ENTRY_ID))

        probe.reads.rows shouldBe 2
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

    /** What the adapter [adapter] last built has read. */
    val reads: Reads

    fun adapter(
        listing: List<String> = emptyList(),
        chapters: Int = 1,
        sourceChapters: List<String> = emptyList(),
        unnumbered: Boolean = false,
        altTitles: List<String> = emptyList(),
        siblingTitle: String? = null,
    ): MigrationFlowAdapter

    /** The stored entry as the flow loads it, placed on [onSource]. */
    fun entry(onSource: String): MigrationEntry

    /** The adapter with sources of [languages] on, which decides whether a row names its source's language. */
    fun withLanguages(languages: Set<String>): MigrationFlowAdapter
}

object MangaProbe : Probe {
    override val oldSource = "1"
    override val target = "2"
    override var reads = Reads()
        private set

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
        altTitles: List<String>,
        siblingTitle: String?,
    ): MigrationFlowAdapter = adapter(
        listing,
        chapters,
        sourceChapters,
        unnumbered,
        setOf("en"),
        altTitles,
        siblingTitle,
    )

    override fun withLanguages(languages: Set<String>) = adapter(languages = languages)

    /** The adapter with [languages] enabled, which decides whether a row names its source's language. */
    fun adapter(
        listing: List<String> = emptyList(),
        chapters: Int = 1,
        sourceChapters: List<String> = emptyList(),
        unnumbered: Boolean = false,
        languages: Set<String>,
        altTitles: List<String> = emptyList(),
        siblingTitle: String? = null,
    ): MigrationFlowAdapter {
        val reads = Reads().also { this.reads = it }
        val rows = mutableMapOf(stored.id to stored, picked.id to picked)
        siblingTitle?.let { rows[SIBLING_ID] = stored.copy(id = SIBLING_ID, url = "/sibling", title = it) }
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
            sourcePreferences = SourcePreferences(InMemoryPreferenceStore()).apply { enabledLanguages.set(languages) },
            getManga = mockk<GetManga> {
                coEvery { await(any<Long>()) } answers {
                    reads.rows++
                    rows[firstArg()]
                }
            },
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
            mergeManager = mockk<MangaMergeManager> {
                coEvery { computeRelatedIds(any()) } answers { relatedIds(firstArg(), siblingTitle) }
            },
            getFavorites = mockk(relaxed = true),
            updateMangaFromRemote = mockk<UpdateMangaFromRemote>(relaxed = true) {
                coEvery { this@mockk.invoke(any<Manga>(), any(), fetchChapters = true, any(), any()) } answers {
                    synced = true
                    Result.failure(IOException("the probe stores no update"))
                }
            },
            getEntryCustomInfo = customInfo(EntryId.Manga(stored.id), altTitles, reads),
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
    override var reads = Reads()
        private set

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
        altTitles: List<String>,
        siblingTitle: String?,
    ): MigrationFlowAdapter = adapter(
        listing,
        chapters,
        sourceChapters,
        unnumbered,
        setOf("en"),
        altTitles,
        siblingTitle,
    )

    override fun withLanguages(languages: Set<String>) = adapter(languages = languages)

    // An enabled source per language; LN plugins declare theirs by name, which the adapter normalises.
    private fun adapter(
        listing: List<String> = emptyList(),
        chapters: Int = 1,
        sourceChapters: List<String> = emptyList(),
        unnumbered: Boolean = false,
        languages: Set<String>,
        altTitles: List<String> = emptyList(),
        siblingTitle: String? = null,
    ): MigrationFlowAdapter {
        val reads = Reads().also { this.reads = it }
        val rows = mutableMapOf(stored.id to stored, picked.id to picked)
        siblingTitle?.let { rows[SIBLING_ID] = stored.copy(id = SIBLING_ID, url = "/sibling", title = it) }
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
                seenNovelSources().set(
                    mapOf("old" to LnSourceIdentity(MigrationAdapterConformanceTest.OLD_NAME, lang = "English")),
                )
            },
        ).also { it.register(targetSource) }
        return NovelMigrationFlowAdapter(
            sourceManager = sourceManager,
            getEnabledNovelSources = mockk {
                coEvery { get() } returns
                    languages.map { lang -> mockk<NovelSource> { every { this@mockk.lang } returns lang } }
            },
            sourcePreferences = mockk(),
            novelPreferences = NovelPreferences(EmittingPreferenceStore()),
            novelRepository = mockk<NovelRepository> {
                coEvery { getById(any()) } answers {
                    reads.rows++
                    rows[firstArg()]
                }
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
            mergeManager = mockk<NovelMergeManager> {
                coEvery { computeRelatedIds(any()) } answers { relatedIds(firstArg(), siblingTitle) }
            },
            installer = mockk(relaxed = true),
            getEntryCustomInfo = customInfo(EntryId.Novel(stored.id), altTitles, reads),
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

private const val SIBLING_ID = 3L

/** Entry row reads and Edit info reads, counted at the repositories an adapter is built over. */
class Reads {
    var rows = 0
    var customInfo = 0
}

private fun <T> Flow<T>.counted(reads: Reads): Flow<T> = onStart { reads.customInfo++ }

/** The entry's merge group: itself and the sibling when there is one, as computeRelatedIds answers. */
private fun relatedIds(id: Long, siblingTitle: String?): LongArray =
    if (siblingTitle != null && id == MigrationAdapterConformanceTest.ENTRY_ID) {
        longArrayOf(id, SIBLING_ID)
    } else {
        longArrayOf(id)
    }

/** The real reader over stored rows: [owner] carries [altTitles] when there are any, nothing else does. */
private fun customInfo(owner: EntryId, altTitles: List<String>, reads: Reads): GetEntryCustomInfo {
    val manga = listOfNotNull(
        (owner as? EntryId.Manga)?.takeIf { altTitles.isNotEmpty() }?.let {
            CustomMangaInfo(it.rawId, altTitles = altTitles)
        },
    )
    val novels = listOfNotNull(
        (owner as? EntryId.Novel)?.takeIf { altTitles.isNotEmpty() }?.let {
            CustomNovelInfo(it.rawId, altTitles = altTitles)
        },
    )
    val mangaRepo = object : CustomMangaInfoRepository {
        override fun getByMangaIdAsFlow(
            mangaId: Long,
        ) = flowOf(manga.firstOrNull { it.mangaId == mangaId }).counted(reads)
        override fun getAllAsFlow() = flowOf(manga).counted(reads)
        override suspend fun getByMangaId(mangaId: Long) = error("not read by migration")
        override suspend fun set(info: CustomMangaInfo) = error("read only")
        override suspend fun delete(mangaId: Long) = error("read only")
    }
    val novelRepo = object : CustomNovelInfoRepository {
        override fun getByNovelIdAsFlow(
            novelId: Long,
        ) = flowOf(novels.firstOrNull { it.novelId == novelId }).counted(reads)
        override fun getAllAsFlow() = flowOf(novels).counted(reads)
        override suspend fun getByNovelId(novelId: Long) = error("not read by migration")
        override suspend fun set(info: CustomNovelInfo) = error("read only")
        override suspend fun delete(novelId: Long) = error("read only")
    }
    return GetEntryCustomInfo(GetCustomMangaInfo(mangaRepo), GetCustomNovelInfo(novelRepo))
}
