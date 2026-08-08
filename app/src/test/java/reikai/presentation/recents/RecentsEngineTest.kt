package reikai.presentation.recents

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.category.RecentsSurface
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.merge.MergeManager
import reikai.domain.source.ReikaiSourcePreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.updates.service.UpdatesPreferences

/**
 * The engine over hand-built providers. The real adapters resolve Injekt at construction and cannot run
 * here, so these fakes stand in for them; the chip is preset before construction because the in-memory
 * preference store's `changes()` never emits, which makes a mid-test flip untestable rather than false.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecentsEngineTest {

    private val store = InMemoryPreferenceStore()
    private val sourcePreferences = ReikaiSourcePreferences(store)
    private val updatesPreferences = UpdatesPreferences(store)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun engine(
        providers: List<RecentsProvider>,
        chip: ContentType = ContentType.ALL,
        lanes: Set<RecentsLaneKind> = setOf(RecentsLaneKind.UPDATED),
    ): RecentsEngine {
        sourcePreferences.updatesContentType.set(chip)
        return RecentsEngine(
            providers = providers,
            surface = RecentsSurface.UPDATES,
            lanes = lanes,
            sourcePreferences = sourcePreferences,
            updatesPreferences = updatesPreferences,
        )
    }

    private suspend fun RecentsEngine.firstAssembly(): RecentsAssembled = assembled.filterNotNull().first()

    private fun item(entryId: EntryId, at: Long, lane: RecentsLane = RecentsLane.Added) =
        RecentsItem(entryId = entryId, timestamp = at, lane = lane, payload = Unit)

    private val manga1 = EntryId.Manga(1)
    private val manga2 = EntryId.Manga(2)
    private val novel1 = EntryId.Novel(1)

    @Test
    fun `both content types assemble into one feed, newest first`() = runTest {
        val engine = engine(
            listOf(
                provider(ContentType.MANGA, updated = rows(item(manga1, at = 100))),
                provider(ContentType.NOVELS, updated = rows(item(novel1, at = 200))),
            ),
        )

        engine.firstAssembly().items.map { it.entryId } shouldContainExactly listOf(novel1, manga1)
    }

    @Test
    fun `the chip selects whose rows assemble`() = runTest {
        val engine = engine(
            listOf(
                provider(ContentType.MANGA, updated = rows(item(manga1, at = 100))),
                provider(ContentType.NOVELS, updated = rows(item(novel1, at = 200))),
            ),
            chip = ContentType.MANGA,
        )

        engine.firstAssembly().items.map { it.entryId } shouldContainExactly listOf(manga1)
    }

    @Test
    fun `the assembly is tagged with the chip that selected it`() = runTest {
        val engine = engine(listOf(provider(ContentType.NOVELS)), chip = ContentType.NOVELS)

        engine.firstAssembly().chip shouldBe ContentType.NOVELS
    }

    @Test
    fun `an unloaded lane on a hidden content type does not hold the visible one`() = runTest {
        val engine = engine(
            listOf(
                provider(ContentType.MANGA, updated = rows(item(manga1, at = 100))),
                provider(ContentType.NOVELS, updated = RecentsLaneRows.Loading),
            ),
            chip = ContentType.MANGA,
        )

        engine.firstAssembly().loading shouldBe false
    }

    @Test
    fun `an unloaded lane does hold a view that shows it`() = runTest {
        val engine = engine(
            listOf(
                provider(ContentType.MANGA, updated = rows(item(manga1, at = 100))),
                provider(ContentType.NOVELS, updated = RecentsLaneRows.Loading),
            ),
        )

        engine.firstAssembly().loading shouldBe true
    }

    @Test
    fun `a loading feed is not an empty one`() = runTest {
        val engine = engine(listOf(provider(ContentType.MANGA, updated = RecentsLaneRows.Loading)))

        engine.firstAssembly().isEmpty shouldBe false
    }

    @Test
    fun `only the lanes this surface renders are collected`() = runTest {
        val readRow = item(manga2, at = 300, lane = RecentsLane.Read(ChapterRef(manga2, chapterId = 1)))
        val engine = engine(
            listOf(provider(ContentType.MANGA, read = rows(readRow))),
            lanes = setOf(RecentsLaneKind.UPDATED),
        )

        engine.firstAssembly().items shouldContainExactly emptyList()
    }

    @Test
    fun `the last updated line is the newer library under All`() = runTest {
        val engine = engine(
            listOf(
                provider(ContentType.MANGA, updatedAt = 5),
                provider(ContentType.NOVELS, updatedAt = 9),
            ),
        )

        engine.lastUpdated.first { it != 0L } shouldBe 9
    }

    @Test
    fun `the last updated line ignores the content type the chip hides`() = runTest {
        val engine = engine(
            listOf(
                provider(ContentType.MANGA, updatedAt = 5),
                provider(ContentType.NOVELS, updatedAt = 9),
            ),
            chip = ContentType.MANGA,
        )

        engine.lastUpdated.first { it != 0L } shouldBe 5
    }

    // Merge membership, pinned here rather than on the kernel: whether merging-off reaches the feed as
    // an empty map is the clause that can actually break, and it lives on this side of the seam.

    private fun mergeManager(memberships: Map<Long, Long>): MergeManager {
        val manager = mockk<MergeManager>(relaxed = true)
        every { manager.membershipChanges() } returns flowOf(memberships)
        return manager
    }

    private fun mergingPreference(enabled: Boolean): Preference<Boolean> {
        val preference = mockk<Preference<Boolean>>(relaxed = true)
        every { preference.changes() } returns flowOf(enabled)
        return preference
    }

    @Test
    fun `memberships arrive keyed by entry so the two content types cannot cross`() = runTest {
        val flow = mergeManager(mapOf(1L to 7L)).membershipFlow(mergingPreference(true), EntryId::Novel)

        flow.first() shouldBe mapOf(EntryId.Novel(1) to 7L)
    }

    @Test
    fun `with merging off the feed is handed no groups at all`() = runTest {
        val flow = mergeManager(mapOf(1L to 7L)).membershipFlow(mergingPreference(false), EntryId::Manga)

        flow.first() shouldBe emptyMap()
    }

    @Test
    fun `a chapter state filter marks a surface that renders the updated lane`() {
        recentsFilterActive(
            byCategory = false,
            byChapterState = true,
            lanes = setOf(RecentsLaneKind.UPDATED),
        ) shouldBe true
    }

    @Test
    fun `a chapter state filter does not mark a surface without the updated lane`() {
        recentsFilterActive(
            byCategory = false,
            byChapterState = true,
            lanes = setOf(RecentsLaneKind.READ),
        ) shouldBe false
    }

    @Test
    fun `a category filter marks every surface`() {
        recentsFilterActive(
            byCategory = true,
            byChapterState = false,
            lanes = setOf(RecentsLaneKind.READ),
        ) shouldBe true
    }
}

private fun rows(vararg items: RecentsItem) = RecentsLaneRows(items.toList(), loaded = true)

private fun provider(
    type: ContentType,
    read: RecentsLaneRows = rows(),
    updated: RecentsLaneRows = rows(),
    added: RecentsLaneRows = rows(),
    updatedAt: Long = 0L,
) = FakeRecentsProvider(type, read, updated, added, updatedAt)

/** A provider with canned lanes. The verbs are unreachable from the engine until selection lands. */
private class FakeRecentsProvider(
    override val contentType: ContentType,
    readRows: RecentsLaneRows,
    updatedRows: RecentsLaneRows,
    addedRows: RecentsLaneRows,
    updatedAt: Long,
) : RecentsProvider {

    override val readLane: Flow<RecentsLaneRows> = flowOf(readRows)
    override val updatedLane: Flow<RecentsLaneRows> = flowOf(updatedRows)
    override val addedLane: Flow<RecentsLaneRows> = flowOf(addedRows)
    override val lastUpdated: Flow<Long> = flowOf(updatedAt)
    override val membership: Flow<Map<EntryId, Long>> = flowOf(emptyMap())

    override suspend fun targetChapter(item: RecentsItem): ChapterRef? = null

    override fun markRead(chapters: Set<ChapterRef>, read: Boolean) = Unit
    override fun setBookmark(chapters: Set<ChapterRef>, bookmarked: Boolean) = Unit
    override fun download(chapters: Set<ChapterRef>) = Unit
    override fun deleteDownloads(chapters: Set<ChapterRef>) = Unit
    override fun removeFromHistory(entries: Set<EntryId>) = Unit
}
