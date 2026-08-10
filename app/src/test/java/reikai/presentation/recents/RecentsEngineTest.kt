package reikai.presentation.recents

import cafe.adriel.voyager.core.screen.Screen
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
import reikai.presentation.browse.AddDecision
import reikai.presentation.browse.AddFavoriteResult
import reikai.presentation.browse.components.EntryDuplicateCardUi
import reikai.presentation.browse.components.EntrySourceLabel
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.category.model.Category
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
    private val category = Category(id = 3L, name = "Reading", order = 0L, flags = 0L)

    private fun duplicates(entry: EntryId) = RecentsDuplicates(
        duplicates = listOf(
            RecentsDuplicate(
                entry,
                EntryDuplicateCardUi(
                    id = entry.rawId,
                    coverModel = Unit,
                    title = "already there",
                    author = null,
                    artist = null,
                    status = 0L,
                    source = EntrySourceLabel.Installed("a source"),
                    chapterCount = 0L,
                ),
            ),
        ),
        groupIdByRawId = emptyMap(),
        suggestGroup = false,
    )

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

    // Search, selection and the verbs: the values the two replaced screens each stored twice.

    private fun ref(entryId: EntryId, chapterId: Long) = ChapterRef(entryId, chapterId)

    @Test
    fun `a search keeps only the rows whose displayed title matches`() = runTest {
        val engine = engine(
            listOf(
                provider(
                    ContentType.MANGA,
                    updated = rows(item(manga1, at = 100), item(manga2, at = 90)),
                    titles = mapOf(manga1 to "Dandadan", manga2 to "Berserk"),
                ),
            ),
        )
        engine.search("dan")

        engine.firstAssembly().items.map { it.entryId } shouldContainExactly listOf(manga1)
    }

    @Test
    fun `an empty query leaves every row`() = runTest {
        val engine = engine(
            listOf(
                provider(
                    ContentType.MANGA,
                    updated = rows(item(manga1, at = 100), item(manga2, at = 90)),
                    titles = mapOf(manga1 to "Dandadan", manga2 to "Berserk"),
                ),
            ),
        )
        engine.search("")

        engine.firstAssembly().items.size shouldBe 2
    }

    @Test
    fun `a range selection spans both content types in the rendered order`() {
        val engine = engine(listOf(provider(ContentType.MANGA), provider(ContentType.NOVELS)))
        val ordered = listOf(ref(manga1, 1), ref(novel1, 2), ref(manga2, 3))

        engine.toggleSelection(ordered.first())
        engine.toggleRangeSelection(ordered.last(), ordered)

        engine.selection.value shouldContainExactlyInAnyOrder ordered
    }

    @Test
    fun `a range with no anchor selects only the row that was pressed`() {
        val engine = engine(listOf(provider(ContentType.MANGA)))
        val ordered = listOf(ref(manga1, 1), ref(manga2, 2))

        engine.toggleRangeSelection(ordered.last(), ordered)

        engine.selection.value shouldContainExactly listOf(ordered.last())
    }

    @Test
    fun `inverting swaps selected for unselected across the rendered order`() {
        val engine = engine(listOf(provider(ContentType.MANGA), provider(ContentType.NOVELS)))
        val ordered = listOf(ref(manga1, 1), ref(novel1, 2))

        engine.toggleSelection(ordered.first())
        engine.invertSelection(ordered)

        engine.selection.value shouldContainExactly listOf(ordered.last())
    }

    @Test
    fun `a selected chapter the feed no longer holds leaves the selection`() = runTest {
        val chapter = ref(manga2, 3)
        val engine = engine(
            listOf(
                provider(
                    ContentType.MANGA,
                    updated = rows(
                        item(manga1, at = 100, lane = RecentsLane.Updated(ref(manga1, 1))),
                        item(manga2, at = 90, lane = RecentsLane.Updated(chapter)),
                    ),
                    titles = mapOf(manga1 to "Dandadan", manga2 to "Berserk"),
                ),
            ),
        )
        engine.toggleSelection(chapter)
        engine.search("dan")
        engine.firstAssembly()

        engine.selection.value shouldBe emptySet()
    }

    @Test
    fun `a bulk action reaches every content type on screen and clears the selection`() {
        val manga = provider(ContentType.MANGA)
        val novel = provider(ContentType.NOVELS)
        val engine = engine(listOf(manga, novel))
        val chapter = ref(manga1, 1)

        engine.toggleSelection(chapter)
        engine.markReadSelection(read = true)

        manga.markedRead shouldBe setOf(chapter)
        novel.markedRead shouldBe setOf(chapter)
        engine.selection.value shouldBe emptySet()
    }

    @Test
    fun `a refresh that starts one library reports a start, not an already-running`() {
        val manga = provider(ContentType.MANGA, refreshStarts = false)
        val novel = provider(ContentType.NOVELS, refreshStarts = true)

        engine(listOf(manga, novel)).refresh() shouldBe true
    }

    @Test
    fun `a refresh reaches the second library even when the first already started`() {
        val manga = provider(ContentType.MANGA, refreshStarts = true)
        val novel = provider(ContentType.NOVELS, refreshStarts = false)

        engine(listOf(manga, novel)).refresh()

        novel.refreshed shouldBe true
    }

    @Test
    fun `a refresh under one chip leaves the other library alone`() {
        val manga = provider(ContentType.MANGA)
        val novel = provider(ContentType.NOVELS)

        engine(listOf(manga, novel), chip = ContentType.MANGA).refresh()

        novel.refreshed shouldBe false
    }

    @Test
    fun `clearing history reaches both content types under All`() {
        val manga = provider(ContentType.MANGA)
        val novel = provider(ContentType.NOVELS)

        engine(listOf(manga, novel)).clearHistory()

        (manga.historyCleared to novel.historyCleared) shouldBe (true to true)
    }

    @Test
    fun `clearing history under one chip spares the other content type`() {
        val manga = provider(ContentType.MANGA)
        val novel = provider(ContentType.NOVELS)

        engine(listOf(manga, novel), chip = ContentType.MANGA).clearHistory()

        novel.historyCleared shouldBe false
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

    @Test
    fun `an add runs through the provider that owns the entry's content type`() = runTest {
        val manga = provider(ContentType.MANGA)
        val novel = provider(ContentType.NOVELS)

        engine(listOf(manga, novel)).startAdd(manga1)

        (manga.addedEntry to novel.addedEntry) shouldBe (manga1 to null)
    }

    @Test
    fun `an entry already in the library is not added again`() = runTest {
        val manga = provider(ContentType.MANGA, decision = AddDecision.Remove)

        engine(listOf(manga)).startAdd(manga1)

        manga.addedEntry shouldBe null
    }

    @Test
    fun `an entry that has gone is not added`() = runTest {
        val manga = provider(ContentType.MANGA, decision = null)

        engine(listOf(manga)).startAdd(manga1)

        manga.addedEntry shouldBe null
    }

    @Test
    fun `a possible duplicate is asked about instead of added`() = runTest {
        val duplicates = duplicates(novel1)
        val manga = provider(ContentType.MANGA, decision = AddDecision.ConfirmDuplicate(duplicates))
        val engine = engine(listOf(manga))

        engine.startAdd(manga1)

        engine.dialog.value shouldBe RecentsDialog.Duplicate(manga1, duplicates)
        manga.addedEntry shouldBe null
    }

    @Test
    fun `an add with no usable default opens the picker the provider answered with`() = runTest {
        val selection = listOf(CheckboxState.State.None(category))
        val manga = provider(ContentType.MANGA, addResult = AddFavoriteResult.NeedsCategoryChoice(selection))
        val engine = engine(listOf(manga))

        engine.startAdd(manga1)

        engine.dialog.value shouldBe RecentsDialog.ChangeCategory(manga1, selection)
    }

    @Test
    fun `adding to a group still asks for categories when the group has none`() = runTest {
        val selection = listOf(CheckboxState.State.None(category))
        val manga = provider(ContentType.MANGA, addResult = AddFavoriteResult.NeedsCategoryChoice(selection))
        val engine = engine(listOf(manga))

        engine.groupAdd(manga1, listOf(manga2))

        manga.groupedWith shouldBe listOf(manga2)
        engine.dialog.value shouldBe RecentsDialog.ChangeCategory(manga1, selection)
    }

    @Test
    fun `the picker's confirm files through the provider that owns the entry`() = runTest {
        val manga = provider(ContentType.MANGA)
        val novel = provider(ContentType.NOVELS)

        engine(listOf(manga, novel)).fileAddCategories(novel1, listOf(3L))

        (manga.filedCategories to novel.filedCategories) shouldBe (null to (novel1 to listOf(3L)))
    }
}

private fun rows(vararg items: RecentsItem) = RecentsLaneRows(items.toList(), loaded = true)

private fun provider(
    type: ContentType,
    read: RecentsLaneRows = rows(),
    updated: RecentsLaneRows = rows(),
    added: RecentsLaneRows = rows(),
    updatedAt: Long = 0L,
    titles: Map<EntryId, String> = emptyMap(),
    refreshStarts: Boolean = true,
    decision: AddDecision<RecentsDuplicates>? = AddDecision.Add,
    addResult: AddFavoriteResult = AddFavoriteResult.Added,
) = FakeRecentsProvider(type, read, updated, added, updatedAt, titles, refreshStarts, decision, addResult)

/** A provider with canned lanes, recording the verbs the engine dispatched to it. */
private class FakeRecentsProvider(
    override val contentType: ContentType,
    readRows: RecentsLaneRows,
    updatedRows: RecentsLaneRows,
    addedRows: RecentsLaneRows,
    updatedAt: Long,
    private val titles: Map<EntryId, String>,
    private val refreshStarts: Boolean,
    private val decision: AddDecision<RecentsDuplicates>?,
    private val addResult: AddFavoriteResult,
) : RecentsProvider {

    var historyCleared = false
        private set
    var markedRead: Set<ChapterRef>? = null
        private set
    var refreshed = false
        private set
    var addedEntry: EntryId? = null
        private set
    var filedCategories: Pair<EntryId, List<Long>>? = null
        private set
    var groupedWith: List<EntryId>? = null
        private set

    override val readLane: Flow<RecentsLaneRows> = flowOf(readRows)
    override val updatedLane: Flow<RecentsLaneRows> = flowOf(updatedRows)
    override val addedLane: Flow<RecentsLaneRows> = flowOf(addedRows)
    override val lastUpdated: Flow<Long> = flowOf(updatedAt)
    override val membership: Flow<Map<EntryId, Long>> = flowOf(emptyMap())

    override fun title(item: RecentsItem): String = titles[item.entryId].orEmpty()

    override suspend fun targetChapter(item: RecentsItem): ChapterRef? = null

    override fun markRead(chapters: Set<ChapterRef>, read: Boolean) {
        markedRead = chapters
    }

    override fun setBookmark(chapters: Set<ChapterRef>, bookmarked: Boolean) = Unit
    override fun download(chapters: Set<ChapterRef>) = Unit
    override fun deleteDownloads(chapters: Set<ChapterRef>) = Unit
    override fun removeFromHistory(entries: Set<EntryId>) = Unit

    override suspend fun addDecision(entry: EntryId): AddDecision<RecentsDuplicates>? = decision

    override suspend fun addToLibrary(entry: EntryId): AddFavoriteResult {
        addedEntry = entry
        return addResult
    }

    override suspend fun applyAddCategories(entry: EntryId, categoryIds: List<Long>) {
        filedCategories = entry to categoryIds
    }

    override suspend fun addToGroup(entry: EntryId, duplicates: List<EntryId>): AddFavoriteResult {
        groupedWith = duplicates
        return addResult
    }

    override fun clearHistory() {
        historyCleared = true
    }

    override fun refresh(): Boolean {
        refreshed = true
        return refreshStarts
    }

    override suspend fun detailsScreen(entry: EntryId): Screen? = null
}
