package reikai.presentation.library

import android.content.Context
import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.category.CATEGORY_HIDDEN_MASK
import reikai.domain.category.CategoryContentType
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR

class LibraryEngineTest {

    private fun provider(type: ContentType, rows: List<LibraryItem> = emptyList()): LibraryProvider {
        val provider = mockk<LibraryProvider>(relaxed = true)
        every { provider.contentType } returns type
        every { provider.rows } returns flowOf(rows)
        every { provider.state } returns MutableStateFlow(screenState)
        every { provider.overlaid(any()) } answers { firstArg() }
        every { provider.trackKey } returns flowOf(null)
        return provider
    }

    private val manga = provider(ContentType.MANGA)
    private val novel = provider(ContentType.NOVELS)
    private val engine = engineOver(listOf(manga, novel))

    /**
     * An engine over a store whose preference flows emit, which the in-memory one's do not: the
     * assembly combines several of them, so on the cheaper store it would never emit at all and every
     * assertion about what it assembled would pass without one having run.
     */
    private fun engineOver(
        providers: List<LibraryProvider>,
        categories: List<Category> = emptyList(),
        sort: LibrarySort = LibrarySort.default,
        groupBy: Int = LibraryGroup.BY_DEFAULT,
    ): LibraryEngine {
        val store = EmittingPreferenceStore()
        val repository = mockk<CategoryRepository>(relaxed = true)
        every { repository.getUnfilteredAsFlow() } returns flowOf(categories)
        val libraryPreferences = LibraryPreferences(store).also { it.sortingMode.set(sort) }
        val reikaiLibraryPreferences = ReikaiLibraryPreferences(store).also { it.groupLibraryBy.set(groupBy) }
        return LibraryEngine(
            providers = providers,
            reikaiLibraryPreferences = reikaiLibraryPreferences,
            libraryPreferences = libraryPreferences,
            categoryRepository = repository,
            setSortModeForCategory = mockk(relaxed = true),
            // Only the dynamic-grouping assembly reaches these, which no case here exercises.
            context = mockk(relaxed = true),
            trackerManager = mockk(relaxed = true),
        )
    }

    private val screenState = LibraryScreenState(
        isLoading = false,
        isLibraryEmpty = false,
        searchQuery = null,
        hasActiveFilters = false,
        showContinueButton = false,
        overlayKey = null,
    )

    private val m1 = EntryId.Manga(1)
    private val m2 = EntryId.Manga(2)
    private val m3 = EntryId.Manga(3)

    // A row id is only unique within one content type, so these two are different entries.
    private val n1 = EntryId.Novel(1)

    private val bucket = "7"

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun row(id: Long, categories: List<Long>): LibraryItem = LibraryItem(
        libraryManga = LibraryManga(
            manga = Manga.create().copy(id = id, title = "Title $id"),
            categories = categories,
            totalChapters = 0,
            readCount = 0,
            bookmarkCount = 0,
            latestUpload = 0,
            chapterFetchedAt = 0,
            lastRead = 0,
        ),
        downloadCount = 0,
        unreadCount = 0,
        isLocal = false,
        badges = LibraryItem.Badges(downloadCount = 0, unreadCount = 0, isLocal = false, sourceLanguage = ""),
        entryId = EntryId.Manga(id),
    )

    /**
     * The prune runs on what the assembly kept, not on the rows it was built from. A hidden category
     * is the reachable case: the entry stays in the provider's rows, so every bulk verb would still
     * resolve it, while no screen shows it. Pruning before the assembly could not see that at all.
     */
    @Test
    fun `a selected entry the assembly dropped leaves the selection`() = runTest {
        val hidden = Category(id = 10, name = "Hidden", order = 0, flags = CATEGORY_HIDDEN_MASK)
        val shown = Category(id = 11, name = "Reading", order = 1, flags = 0)
        val provider = provider(
            ContentType.MANGA,
            rows = listOf(row(1, categories = listOf(10)), row(2, categories = listOf(11))),
        )
        val engine = engineOver(listOf(provider), categories = listOf(hidden, shown))
        engine.toggleSelection(bucketKey = "10", entry = m1)
        engine.toggleSelection(bucketKey = "11", entry = m2)

        engine.assembled.filterNotNull().first()

        engine.selection.value shouldContainExactly listOf(m2)
    }

    @Test
    fun `a pruned entry stays out when another is picked`() = runTest {
        val hidden = Category(id = 10, name = "Hidden", order = 0, flags = CATEGORY_HIDDEN_MASK)
        val shown = Category(id = 11, name = "Reading", order = 1, flags = 0)
        val provider = provider(
            ContentType.MANGA,
            rows = listOf(row(1, categories = listOf(10)), row(2, categories = listOf(11)), row(3, listOf(11))),
        )
        val engine = engineOver(listOf(provider), categories = listOf(hidden, shown))
        engine.toggleSelection(bucketKey = "10", entry = m1)
        engine.toggleSelection(bucketKey = "11", entry = m2)
        engine.assembled.filterNotNull().first()

        engine.toggleSelection(bucketKey = "11", entry = m3)

        engine.selection.value shouldContainExactlyInAnyOrder listOf(m2, m3)
    }

    @Test
    fun `a novel-only category is no section under the Manga chip`() = runTest {
        val novelOnly =
            Category(id = 12, name = "Novels", order = 0, flags = 0, contentType = CategoryContentType.NOVEL)
        val shown = Category(id = 11, name = "Reading", order = 1, flags = 0)
        val provider = provider(
            ContentType.MANGA,
            rows = listOf(row(1, categories = listOf(12)), row(2, categories = listOf(11))),
        )
        val engine = engineOver(listOf(provider), categories = listOf(novelOnly, shown))

        val assembled = engine.assembled.filterNotNull().first()

        assembled.buckets.map { it.key } shouldContainExactly listOf("11")
    }

    /** A loaded manga library beside a novel one still loading, under the Manga chip a fresh store starts on. */
    private fun engineWithNovelsLoading(): LibraryEngine {
        val novels = provider(ContentType.NOVELS)
        every { novels.rows } returns flowOf(null)
        return engineOver(
            listOf(provider(ContentType.MANGA, rows = listOf(row(1, categories = listOf(11)))), novels),
            categories = listOf(Category(id = 11, name = "Reading", order = 0, flags = 0)),
        )
    }

    /**
     * A view built without one of its types shows that type's entries missing, and its counts short,
     * until the real rows land. The tab draws a view with no assembly as loading, so none is the answer.
     */
    @Test
    fun `a view waits for every provider in it to load`() = runTest {
        val engine = engineWithNovelsLoading()
        // The Manga view assembling first is what shows the engine ran before the chip moved.
        engine.assembled.filterNotNull().first()

        engine.setContentType(ContentType.ALL)

        engine.assembled.first { it?.chip != ContentType.MANGA } shouldBe null
    }

    @Test
    fun `a provider outside the view does not hold it back`() = runTest {
        val engine = engineWithNovelsLoading()

        val assembled = engine.assembled.filterNotNull().first()

        assembled.presentIds shouldContainExactly setOf(m1)
    }

    @Test
    fun `a running update behind the chip shows as refreshing`() = runTest {
        every { novel.updating } returns flowOf(true)
        every { manga.updating } returns flowOf(false)
        engine.setContentType(ContentType.NOVELS)

        engine.refreshing.first { it } shouldBe true
    }

    private val reading = Category(id = 11, name = "Reading", order = 0, flags = 0)

    /**
     * Track data is read on demand inside the assembly and a track write leaves the rows equal, so only
     * the provider's track key can make the assembly run again after a score edit.
     */
    @Test
    fun `a score change alone reorders the tracker score sort`() = runTest {
        val means = MutableStateFlow(mapOf(1L to 9.0, 2L to 1.0))
        val provider = provider(ContentType.MANGA, rows = listOf(row(1, listOf(11)), row(2, listOf(11))))
        every { provider.trackerMeans() } answers { means.value }
        every { provider.trackKey } returns means
        val engine = engineOver(
            listOf(provider),
            categories = listOf(reading),
            sort = LibrarySort(LibrarySort.Type.TrackerMean, LibrarySort.Direction.Descending),
        )
        engine.assembled.first { it?.firstEntry() == m1 }

        means.value = mapOf(1L to 1.0, 2L to 9.0)

        engine.assembled.first { it?.firstEntry() == m2 }?.firstEntry() shouldBe m2
    }

    @Test
    fun `a status change alone moves the entry to its new tracking status group`() = runTest {
        val statuses = MutableStateFlow(mapOf<EntryId, String>(m1 to "Reading"))
        val provider = provider(ContentType.MANGA, rows = listOf(row(1, listOf(11))))
        coEvery { provider.dynamicGroupingFeed(any()) } answers {
            DynamicGroupingFeed(items = listOf(DynItem(m1, null, null, null)), trackStatuses = statuses.value)
        }
        every { provider.trackKey } returns statuses
        val engine = engineOver(listOf(provider), groupBy = LibraryGroup.BY_TRACK_STATUS)
        engine.assembled.first { it?.buckets?.singleOrNull()?.key == "reading" }

        statuses.value = mapOf(m1 to "Completed")

        engine.assembled.first { it?.buckets?.singleOrNull()?.key == "completed" }
            ?.buckets?.single()?.key shouldBe "completed"
    }

    @Test
    fun `grouping by language names a multi-language source as Browse does`() = runTest {
        mockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
        try {
            every { any<Context>().stringResource(MR.strings.multi_lang) } returns "Multi"
            val provider = provider(ContentType.MANGA, rows = listOf(row(1, listOf(11))))
            coEvery { provider.dynamicGroupingFeed(any()) } returns DynamicGroupingFeed(
                items = listOf(DynItem(m1, null, null, null)),
                languageCodes = mapOf(m1 to "all"),
            )
            val engine = engineOver(listOf(provider), groupBy = LibraryGroup.BY_LANGUAGE)

            val bucket = engine.assembled.first { it?.buckets?.isNotEmpty() == true }?.buckets?.single()

            (bucket as LibraryBucket.Dynamic).label shouldBe "Multi"
        } finally {
            unmockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
        }
    }

    private fun LibraryAssembled.firstEntry() = buckets.firstOrNull()?.let { itemsFor(it).first().entryId }

    /** What the Manga chip hands the assembly while novel rows and one manga state are live. */
    private fun TestScope.mangaChipInputs(
        novelRows: Flow<List<LibraryItem>?>,
        mangaState: StateFlow<LibraryScreenState> = MutableStateFlow(screenState),
        mangaRows: Flow<List<LibraryItem>?> = flowOf(listOf(row(1, listOf(11)))),
    ): List<ActiveAssemblyInputs> {
        val manga = provider(ContentType.MANGA)
        every { manga.rows } returns mangaRows
        every { manga.state } returns mangaState
        val novels = provider(ContentType.NOVELS)
        every { novels.rows } returns novelRows
        val emitted = mutableListOf<ActiveAssemblyInputs>()
        backgroundScope.launch {
            activeAssemblyInputs(flowOf(ContentType.MANGA), listOf(manga, novels)).toList(emitted)
        }
        return emitted
    }

    @Test
    fun `a row change on the type the chip hides never reaches the assembly`() = runTest(UnconfinedTestDispatcher()) {
        val novelRows = MutableStateFlow<List<LibraryItem>?>(listOf(row(1, listOf(11))))
        val emitted = mangaChipInputs(novelRows)

        novelRows.value = listOf(row(2, listOf(11)))

        emitted.size shouldBe 1
    }

    @Test
    fun `a row change on the type the chip shows reaches the assembly`() = runTest(UnconfinedTestDispatcher()) {
        val mangaRows = MutableStateFlow<List<LibraryItem>?>(listOf(row(1, listOf(11))))
        val emitted = mangaChipInputs(flowOf(emptyList()), mangaRows = mangaRows)

        mangaRows.value = listOf(row(2, listOf(11)))

        emitted.size shouldBe 2
    }

    /** The rows stay equal through an overlay edit, so only the overlay key can repaint the title. */
    @Test
    fun `an overlay edit on the shown type reaches the assembly`() = runTest(UnconfinedTestDispatcher()) {
        val mangaState = MutableStateFlow(screenState)
        val emitted = mangaChipInputs(flowOf(emptyList()), mangaState = mangaState)

        mangaState.value = screenState.copy(overlayKey = mapOf(1L to "Renamed"))

        emitted.size shouldBe 2
    }

    @Test
    fun `a model still loading hands over no rows`() = runTest {
        val loaded = listOf(row(1, categories = emptyList()))

        val handed = flowOf(true to emptyList(), false to loaded)
            .loadedRows(isLoading = { it.first }) { it.second }
            .toList()

        handed shouldBe listOf(null, loaded)
    }

    /**
     * A dialog carries the entries it was opened with, and the prune cannot reach that captured copy,
     * so a confirm is held to what the selection still holds. Without it an entry the library dropped
     * while the dialog sat open is deleted anyway, which is the one outcome nobody can undo.
     */
    @Test
    fun `a confirm acts only on entries the selection still holds`() {
        engine.toggleSelection(bucket, m1)

        engine.deleteEntries(
            entries = setOf(m1, m2),
            deleteFromLibrary = true,
            deleteDownloads = false,
            removeGroupedSources = false,
        )

        verify { manga.deleteEntries(setOf(m1), true, false, false) }
    }

    @Test
    fun `a category confirm is held to the same set`() {
        engine.toggleSelection(bucket, m1)

        engine.setCategories(entries = setOf(m1, m2), addCategories = listOf(3L), removeCategories = emptyList())

        verify { manga.setCategories(setOf(m1), listOf(3L), emptyList()) }
    }

    /** Each chip's pager indexes its own category list, so a swipe under All must not move Manga's restore page. */
    @Test
    fun `a swipe under All leaves the Manga chip's restore page`() {
        engine.updateActiveCategoryIndex(ContentType.MANGA, 2)

        engine.updateActiveCategoryIndex(ContentType.ALL, 3)

        engine.initialPageFor(ContentType.MANGA) shouldBe 2
    }

    /** A page index describes this device's category list, so no chip's may travel in a backup. */
    @ParameterizedTest
    @EnumSource(ContentType::class)
    fun `every chip keeps its restore page as app state`(type: ContentType) {
        Preference.isAppState(engine.lastUsedCategoryPref(type).key()) shouldBe true
    }

    @Test
    fun `a single content type drives its own provider`() {
        engine.behaviorFor(ContentType.MANGA) shouldBe manga
        engine.behaviorFor(ContentType.NOVELS) shouldBe novel
    }

    @Test
    fun `ALL fans out to every provider`() {
        engine.providersFor(ContentType.ALL) shouldContainExactly listOf(manga, novel)
    }

    @Test
    fun `a mixed view fails loudly instead of rendering one content type`() {
        shouldThrow<IllegalStateException> { engine.behaviorFor(ContentType.ALL) }
    }

    @Test
    fun `flipping the chip drops the selection`() {
        engine.setContentType(ContentType.MANGA)
        engine.toggleSelection(bucket, m1)

        engine.setContentType(ContentType.NOVELS)

        engine.selection.value.isEmpty() shouldBe true
    }

    /** The tab row fires again on the chip already shown, which is not a flip. */
    @Test
    fun `re-selecting the chip shown keeps the selection`() {
        engine.setContentType(ContentType.MANGA)
        engine.toggleSelection(bucket, m1)

        engine.setContentType(ContentType.MANGA)

        engine.selection.value shouldContainExactly setOf(m1)
    }

    @Test
    fun `toggling adds then removes an entry`() {
        engine.toggleSelection(bucket, m1)
        engine.selection.value shouldContainExactly setOf(m1)

        engine.toggleSelection(bucket, m1)
        engine.selection.value.isEmpty() shouldBe true
    }

    @Test
    fun `entries of different content types sharing a row id stay distinct`() {
        engine.toggleSelection(bucket, m1)
        engine.toggleSelection(bucket, n1)
        engine.selection.value shouldContainExactlyInAnyOrder listOf(m1, n1)
    }

    @Test
    fun `a range select spans both content types`() {
        val ordered = listOf(m1, n1, m2)
        engine.toggleSelection(bucket, m1)
        engine.toggleRangeSelection(bucket, m2, ordered)
        engine.selection.value shouldContainExactlyInAnyOrder ordered
    }

    @Test
    fun `a long press on an already-selected entry drops it, as it does everywhere else`() {
        val ordered = listOf(m1, m2)
        engine.toggleSelection(bucket, m1)
        engine.toggleRangeSelection(bucket, m2, ordered)
        engine.toggleRangeSelection(bucket, m2, ordered)
        engine.selection.value shouldContainExactlyInAnyOrder listOf(m1)
    }

    @Test
    fun `a range select in a different category selects only the tapped entry`() {
        engine.toggleSelection(bucket, m1)
        engine.toggleRangeSelection("8", m3, listOf(m1, m2, m3))
        engine.selection.value shouldContainExactlyInAnyOrder listOf(m1, m3)
    }

    @Test
    fun `selecting all in a category deselects them when all are already selected`() {
        val ordered = listOf(m1, m2)
        engine.selectAllInCategory(ordered)
        engine.selection.value shouldContainExactlyInAnyOrder ordered

        engine.selectAllInCategory(ordered)
        engine.selection.value.isEmpty() shouldBe true
    }

    @Test
    fun `inverting swaps selected for unselected within the category`() {
        engine.toggleSelection(bucket, m1)
        engine.invertSelection(listOf(m1, m2, m3))
        engine.selection.value shouldContainExactlyInAnyOrder listOf(m2, m3)
    }

    @Test
    fun `a bulk action reaches every provider and clears the selection`() {
        engine.toggleSelection(bucket, m1)
        engine.markReadSelection(ContentType.ALL, read = true)

        io.mockk.verify { manga.markReadSelection(setOf(m1), true) }
        io.mockk.verify { novel.markReadSelection(setOf(m1), true) }
        engine.selection.value.isEmpty() shouldBe true
    }

    /** The search field stays on screen across a chip flip, so the view flipped to must filter by it. */
    private fun typeThenSwitch(from: ContentType, typedOn: LibraryProvider, to: ContentType) {
        engine.setContentType(from)
        every { typedOn.state } returns MutableStateFlow(screenState.copy(searchQuery = "Beast Tamer"))
        engine.setContentType(to)
    }

    @Test
    fun `a query typed on Manga filters Novels after the switch`() {
        typeThenSwitch(from = ContentType.MANGA, typedOn = manga, to = ContentType.NOVELS)

        verify { novel.search("Beast Tamer") }
    }

    @Test
    fun `a query typed on Novels filters Manga after the switch`() {
        typeThenSwitch(from = ContentType.NOVELS, typedOn = novel, to = ContentType.MANGA)

        verify { manga.search("Beast Tamer") }
    }

    @Test
    fun `a query typed on Manga filters the novel half of All after the switch`() {
        typeThenSwitch(from = ContentType.MANGA, typedOn = manga, to = ContentType.ALL)

        verify { novel.search("Beast Tamer") }
    }

    @Test
    fun `opening a dialog keeps the selection until the dialog resolves`() {
        engine.toggleSelection(bucket, m1)
        engine.openDeleteDialog(ContentType.MANGA)
        engine.selection.value shouldContainExactly setOf(m1)
    }
}
