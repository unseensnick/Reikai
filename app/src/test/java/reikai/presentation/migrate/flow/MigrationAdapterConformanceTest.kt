package reikai.presentation.migrate.flow

import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.entry.EntryId
import reikai.domain.novel.LnSourceIdentity
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.host.NovelItem
import reikai.novel.source.NovelItemsPage
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager

/**
 * The rules both migration adapters must answer alike, run over each real adapter with its engine
 * mocked at the repository and source boundary. The world is the same for both: one stored entry
 * (id [ENTRY_ID], url [OWN_URL]) on an uninstalled source last seen as [OLD_NAME], and an installed
 * target source listing the given urls, where every stored row has the given number of chapters.
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
    fun `resolving a target that already has chapters is not a fresh sync`(probe: Probe) = runTest {
        val adapter = probe.adapter(listing = listOf("/t"), chapters = 3)
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
    fun `an entry from an uninstalled source is still named`(probe: Probe) = runTest {
        probe.adapter().loadEntries(listOf(ENTRY_ID)).single().sourceName shouldBe OLD_NAME
    }

    companion object {
        const val ENTRY_ID = 1L
        const val OWN_URL = "/own"
        const val OLD_NAME = "Old source"

        @JvmStatic
        fun probes() = listOf(MangaProbe, NovelProbe)
    }
}

/** One content type's adapter over the shared world [MigrationAdapterConformanceTest] describes. */
sealed interface Probe {
    val oldSource: String
    val target: String

    fun adapter(listing: List<String> = emptyList(), chapters: Int = 1): MigrationFlowAdapter

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

    override fun adapter(listing: List<String>, chapters: Int): MigrationFlowAdapter {
        val rows = mutableMapOf(stored.id to stored)
        val catalogue = mockk<CatalogueSource>(relaxed = true) {
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
                coEvery { await(any(), any()) } returns List(chapters) {
                    Chapter.create().copy(id = it + 1L, chapterNumber = it + 1.0)
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
            updateMangaFromRemote = mockk(relaxed = true),
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

    override fun adapter(listing: List<String>, chapters: Int): MigrationFlowAdapter {
        val rows = mutableMapOf(stored.id to stored)
        val targetSource = mockk<NovelSource>(relaxed = true) {
            every { id } returns "target"
            every { name } returns "Target"
            coEvery { search(any(), any(), any()) } returns
                NovelItemsPage(listing.map { NovelItem(it, it, null) }, hasNextPage = false)
        }
        val sourceManager = NovelSourceManager(
            installer = { mockk(relaxed = true) },
            extensionManager = mockk<ExtensionManager> {
                every { loadedNovelExtensionsFlow } returns MutableStateFlow(emptyList())
            },
            prefs = mockk<NovelPreferences> {
                every { seenNovelSources() } returns mockk(relaxed = true) {
                    every { get() } returns mapOf("old" to LnSourceIdentity(MigrationAdapterConformanceTest.OLD_NAME))
                }
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
                coEvery { getByNovelId(any()) } answers { List(chapters) { storedChapter(firstArg(), it + 1.0) } }
            },
            libraryPreferences = mockk(relaxed = true),
            coverCache = mockk(relaxed = true),
            downloadManagerProvider = { mockk(relaxed = true) },
            migrateNovel = mockk(),
            mergeManager = mockk(),
            installer = mockk(relaxed = true),
        )
    }

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
        dateFetch = 0L,
        dateUpload = 0L,
        page = "",
    )
}
