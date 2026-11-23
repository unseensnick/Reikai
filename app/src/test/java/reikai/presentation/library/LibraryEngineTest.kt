package reikai.presentation.library

import android.content.Context
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.category.CATEGORY_HIDDEN_MASK
import reikai.domain.category.CategoryContentType
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.library.FakeLibraryHiddenRepository
import reikai.domain.library.FakeLibraryManualOrderRepository
import reikai.domain.library.HideSeries
import reikai.domain.library.ManualOrderPosition
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.EntryMergeManager
import reikai.presentation.MainDispatcherExtension
import reikai.presentation.recents.EmittingPreferenceStore
import reikai.util.testAppScope
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

    // Declared before the engine field below, which tracks through it. Every engine is tracked: its
    // assembly runs on Dispatchers.Default under a stop timeout and otherwise outlives the test.
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

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
    @Test
    fun `a search for a hidden type lands on the shown type`() {
        val manga = provider(ContentType.MANGA)
        val engine = engineOver(listOf(manga, provider(ContentType.NOVELS)), shown = ContentType.MANGA)

        engine.searchFrom(ContentType.NOVELS, "q")

        verify { manga.search("q") }
    }

    @Test
    fun `a search lands on its own type while both show`() {
        val novels = provider(ContentType.NOVELS)
        val engine = engineOver(listOf(provider(ContentType.MANGA), novels))

        engine.searchFrom(ContentType.NOVELS, "q")

        verify { novels.search("q") }
    }

    private fun engineOver(
        providers: List<LibraryProvider>,
        categories: List<Category> = emptyList(),
        sort: LibrarySort = LibrarySort.default,
        groupBy: Int = LibraryGroup.BY_DEFAULT,
        shown: ContentType = ContentType.ALL,
        manualOrder: FakeLibraryManualOrderRepository = FakeLibraryManualOrderRepository(),
        hides: FakeLibraryHiddenRepository = FakeLibraryHiddenRepository(),
        mergingEnabled: Boolean = true,
        store: EmittingPreferenceStore = EmittingPreferenceStore(),
    ): LibraryEngine {
        val uiPreferences = UiPreferences(store)
        val repository = mockk<CategoryRepository>(relaxed = true)
        every { repository.getUnfilteredAsFlow() } returns flowOf(categories)
        val libraryPreferences = LibraryPreferences(store).also { it.sortingMode.set(sort) }
        val reikaiLibraryPreferences = ReikaiLibraryPreferences(store).also {
            it.groupLibraryBy.set(groupBy)
            it.shownContent.set(shown)
            it.seriesMergingEnabled.set(mergingEnabled)
        }
        return main.track(
            LibraryEngine(
                providers = providers,
                reikaiLibraryPreferences = reikaiLibraryPreferences,
                libraryPreferences = libraryPreferences,
                categoryRepository = repository,
                setSortModeForCategory = mockk(relaxed = true),
                uiPreferences = uiPreferences,
                // Only the dynamic-grouping assembly reaches these, which no case here exercises.
                context = mockk(relaxed = true),
                trackerManager = mockk(relaxed = true),
                manualOrderRepository = manualOrder,
                appScope = testAppScope(),
                combineLocalVolumes = mockk(relaxed = true),
                hideSeries = HideSeries(hides, soloGroups(), soloGroups()),
            ),
        )
    }

    /** A merge manager whose every entry is its own group, so a hide writes the entry alone. */
    private inline fun <reified M : EntryMergeManager> soloGroups(): M = mockk {
        coEvery { groupLibraryMembers(any()) } answers { listOf(firstArg()) }
    }

    /** Manga 1 hidden; manga 2 not; novel 1, sharing manga 1's row id, not; novel 5 hidden through member 9. */
    private fun engineWithHides(): LibraryEngine {
        val reading = Category(id = 11, name = "Reading", order = 0, flags = 0)
        val manga = provider(ContentType.MANGA, rows = listOf(row(1, listOf(11)), row(2, listOf(11))))
        val novels = provider(
            ContentType.NOVELS,
            rows = listOf(
                row(1, listOf(11)).copy(entryId = n1),
                row(5, listOf(11)).copy(entryId = EntryId.Novel(5), relatedMangaIds = listOf(5L, 9L)),
            ),
        )
        val hides = FakeLibraryHiddenRepository(setOf(m1, EntryId.Novel(9)))
        return engineOver(listOf(manga, novels), categories = listOf(reading), hides = hides)
            .also { it.setContentType(ContentType.ALL) }
    }

    @Test
    fun `a hidden series leaves the assembly, either type, a merged one through any member`() = runTest {
        engineWithHides().assembled.first { it?.chip == ContentType.ALL }!!.presentIds shouldContainExactlyInAnyOrder
            listOf(m2, n1)
    }

    @Test
    fun `a view whose every series is hidden reads as all hidden, either type`() = runTest {
        val manga = provider(ContentType.MANGA, rows = listOf(row(1, listOf(11))))
        val novels = provider(ContentType.NOVELS, rows = listOf(row(1, listOf(11)).copy(entryId = n1)))
        val engine = engineOver(
            listOf(manga, novels),
            categories = listOf(Category(id = 11, name = "Reading", order = 0, flags = 0)),
            hides = FakeLibraryHiddenRepository(setOf(m1, n1)),
        ).also { it.setContentType(ContentType.ALL) }

        engine.assembled.first { it?.chip == ContentType.ALL }!!.isAllHidden shouldBe true
    }

    @Test
    fun `a view whose shown series sit in hidden categories is not all hidden`() = runTest {
        val hiddenCategory = Category(id = 12, name = "Later", order = 1, flags = CATEGORY_HIDDEN_MASK)
        val manga = provider(ContentType.MANGA, rows = listOf(row(1, listOf(11)), row(2, listOf(12))))
        val novels = provider(ContentType.NOVELS, rows = listOf(row(1, listOf(12)).copy(entryId = n1)))
        val engine = engineOver(
            listOf(manga, novels),
            categories = listOf(Category(id = 11, name = "Reading", order = 0, flags = 0), hiddenCategory),
            hides = FakeLibraryHiddenRepository(setOf(m1)),
        ).also { it.setContentType(ContentType.ALL) }

        engine.assembled.first { it?.chip == ContentType.ALL }!!.isAllHidden shouldBe false
    }

    @Test
    fun `a hidden series' tags are not offered, either type`() {
        val mangaRows = listOf(
            SeriesTagRow(row(5, emptyList()).copy(relatedMangaIds = listOf(5L, 6L)), listOf("Action")),
            SeriesTagRow(row(7, emptyList()), listOf("Drama")),
        )
        val novelRows = listOf(
            SeriesTagRow(
                row(5, emptyList()).copy(relatedMangaIds = listOf(5L, 9L), entryId = EntryId.Novel(5)),
                listOf("Romance"),
            ),
            SeriesTagRow(row(6, emptyList()).copy(entryId = EntryId.Novel(6)), listOf("Mystery")),
        )
        val manga = provider(ContentType.MANGA).also { every { it.seriesTagRows() } returns mangaRows }
        val novels = provider(ContentType.NOVELS).also { every { it.seriesTagRows() } returns novelRows }
        val hides = FakeLibraryHiddenRepository(setOf(EntryId.Manga(6), EntryId.Novel(9)))
        val engine = engineOver(listOf(manga, novels), hides = hides)

        engine.settingsFor(ContentType.ALL).tagFilter.seriesTags() shouldBe listOf(listOf("Drama"), listOf("Mystery"))
    }

    @Test
    fun `hiding the selection hides each selected series, either type`() = runTest {
        val hides = FakeLibraryHiddenRepository()
        val engine = engineOver(listOf(provider(ContentType.MANGA), provider(ContentType.NOVELS)), hides = hides)
        engine.toggleSelection(bucket, m2)
        engine.toggleSelection(bucket, n1)

        engine.hideSelection()

        // The write runs on the app scope, one series at a time, so wait for the last of the two.
        hides.rows.first { it.size == 2 } shouldContainExactlyInAnyOrder listOf(m2, n1)
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

    private fun row(id: Long, categories: List<Long>): LibraryItem = LibraryItem(
        libraryManga = LibraryManga(
            manga = Manga.create().copy(id = id, title = "Title $id"),
            categories = categories,
            totalChapters = 0,
            readCount = 0,
            bookmarkCount = 0,
            latestUpload = null,
            chapterFetchedAt = null,
            lastRead = null,
        ),
        downloadCount = 0,
        unreadCount = 0,
        isLocal = false,
        badges = LibraryItem.Badges(downloadCount = 0, unreadCount = 0, isLocal = false, sourceLanguage = ""),
        entryId = EntryId.Manga(id),
    )

    private val defaultCategory = Category(id = 0, name = "", order = -1, flags = 0)

    /** A merged tile's lead can change after a restitch, so every member keeps the tile's place. */
    @Test
    fun `a drop stores each merged member at its tile's place`() = runTest {
        val manualOrder = FakeLibraryManualOrderRepository()
        val engine = engineOver(listOf(provider(ContentType.MANGA)), manualOrder = manualOrder)
        val merged = row(5, emptyList()).copy(relatedMangaIds = listOf(5L, 6L))

        engine.moveInCategory(defaultCategory, listOf(row(1, emptyList()), merged))

        manualOrder.positions.first { it.isNotEmpty() }.map { it.entryId to it.position }.toSet() shouldBe
            setOf(1L to 0L, 5L to 1L, 6L to 1L)
    }

    @Test
    fun `undo puts back the order a drop replaced`() = runTest {
        val manualOrder = FakeLibraryManualOrderRepository()
        val before =
            listOf(ManualOrderPosition(ContentType.MANGA, 2, 0, 0), ManualOrderPosition(ContentType.MANGA, 1, 0, 1))
        manualOrder.positions.value = before
        val engine = engineOver(listOf(provider(ContentType.MANGA)), manualOrder = manualOrder)
        engine.moveInCategory(defaultCategory, listOf(row(1, emptyList()), row(2, emptyList())))
        manualOrder.positions.first { it != before }

        engine.undoManualMove()

        manualOrder.positions.first { it.toSet() == before.toSet() }.toSet() shouldBe before.toSet()
    }

    /** A tap can land before the drop's write has, so Undo waits on the drop it undoes. */
    @Test
    fun `an undo tapped straight after a drop still puts the order back`() = runTest {
        val manualOrder = FakeLibraryManualOrderRepository()
        val before =
            listOf(ManualOrderPosition(ContentType.MANGA, 2, 0, 0), ManualOrderPosition(ContentType.MANGA, 1, 0, 1))
        manualOrder.positions.value = before
        val engine = engineOver(listOf(provider(ContentType.MANGA)), manualOrder = manualOrder)

        engine.moveInCategory(defaultCategory, listOf(row(1, emptyList()), row(2, emptyList())))
        engine.undoManualMove()

        // Waits on the two writes: the restored order equals the first one, which a collector would not see again.
        manualOrder.replacements.first { it == 2 }

        manualOrder.positions.value.toSet() shouldBe before.toSet()
    }

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
                languageCodes = mapOf(m1 to listOf("all")),
            )
            val engine = engineOver(listOf(provider), groupBy = LibraryGroup.BY_LANGUAGE)

            val bucket = engine.assembled.first { it?.buckets?.isNotEmpty() == true }?.buckets?.single()

            (bucket as LibraryBucket.Dynamic).label shouldBe "Multi"
        } finally {
            unmockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
        }
    }

    /** The toolbar and the grid read a bucket's list every frame, so the overlay pass must run once. */
    @Test
    fun `a bucket's display list is built once per assembly`() = runTest {
        val provider = provider(ContentType.MANGA, rows = listOf(row(1, listOf(11)), row(2, listOf(11))))
        val assembled = engineOver(listOf(provider), categories = listOf(reading)).assembled.filterNotNull().first()
        val bucket = assembled.buckets.single()

        assembled.itemsFor(bucket) shouldBeSameInstanceAs assembled.itemsFor(bucket)
    }

    /** The tab reads every bucket's size on Main, so an overlay run at the read ran over the library there. */
    @Test
    fun `the overlay runs with the assembly, off the thread that reads the list`() = runTest {
        val overlaidOn = mutableListOf<String>()
        val provider = provider(ContentType.MANGA, rows = listOf(row(1, listOf(11)), row(2, listOf(11))))
        every { provider.overlaid(any()) } answers {
            // Coroutine debug mode appends " @coroutine#N" to the thread name.
            synchronized(overlaidOn) { overlaidOn += Thread.currentThread().name.substringBefore(" @") }
            firstArg()
        }
        val assembled = engineOver(listOf(provider), categories = listOf(reading)).assembled.filterNotNull().first()
        val reader = Thread.currentThread().name.substringBefore(" @")

        assembled.itemsFor(assembled.buckets.single())

        (overlaidOn.isNotEmpty() to overlaidOn.none { it == reader }) shouldBe (true to true)
    }

    /** The memo must never outlive its assembly: a custom title edit has to repaint the row. */
    @Test
    fun `a custom-info edit alone re-emits the assembly with the overlaid title`() = runTest {
        val titles = MutableStateFlow(mapOf<Long, String>())
        val state = MutableStateFlow(screenState)
        val provider = provider(ContentType.MANGA, rows = listOf(row(1, listOf(11))))
        every { provider.state } returns state
        every { provider.overlaid(any()) } answers {
            val item = firstArg<LibraryItem>()
            val custom = titles.value[item.id] ?: return@answers item
            item.copy(libraryManga = item.libraryManga.copy(manga = item.libraryManga.manga.copy(title = custom)))
        }
        val engine = engineOver(listOf(provider), categories = listOf(reading))
        val before = engine.assembled.filterNotNull().first()
        // Read once, as the tab's first frame does, so a memo is holding the old title.
        before.itemsFor(before.buckets.single()).single().libraryManga.manga.title shouldBe "Title 1"

        titles.value = mapOf(1L to "Custom")
        state.value = screenState.copy(overlayKey = titles.value)

        val after = engine.assembled.first { it != null && it !== before }!!
        after.itemsFor(after.buckets.single()).single().libraryManga.manga.title shouldBe "Custom"
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

    /** The tab row fires again on the chip already shown, which is not a flip. */
    @Test
    fun `flipping the chip drops the selection, re-selecting the chip shown keeps it`() {
        engine.setContentType(ContentType.MANGA)
        engine.toggleSelection(bucket, m1)

        engine.setContentType(ContentType.MANGA)
        engine.selection.value shouldContainExactly setOf(m1)

        engine.setContentType(ContentType.NOVELS)
        engine.selection.value.isEmpty() shouldBe true
    }

    @Test
    fun `entries of different content types sharing a row id stay distinct`() {
        engine.toggleSelection(bucket, m1)
        engine.toggleSelection(bucket, n1)
        engine.selection.value shouldContainExactlyInAnyOrder listOf(m1, n1)
    }

    // The engine's one long-press wiring: a range across both types, then a drop on a selected entry,
    // which only rangeOrToggle answers both ways.
    @Test
    fun `a long press ranges across both content types and drops an entry already selected`() {
        val ordered = listOf(m1, n1, m2)
        engine.toggleSelection(bucket, m1)
        engine.toggleRangeSelection(bucket, m2, ordered)
        engine.selection.value shouldContainExactlyInAnyOrder ordered

        engine.toggleRangeSelection(bucket, m2, ordered)
        engine.selection.value shouldContainExactlyInAnyOrder listOf(m1, n1)
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

    /** A local entry has nothing to download, whichever type it is, so its selection offers no Download. */
    @ParameterizedTest
    @EnumSource(value = ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a selection of one local entry hides bulk Download`(type: ContentType) {
        val local = if (type == ContentType.MANGA) m1 else n1
        every { providerOf(type).containsLocal(setOf(local)) } returns true
        engine.toggleSelection(bucket, local)

        engine.canDownloadSelection(ContentType.ALL) shouldBe false
    }

    @Test
    fun `a local manga selected beside a novel keeps bulk Download`() {
        every { manga.containsLocal(setOf(m1, n1)) } returns true
        engine.toggleSelection(bucket, m1)
        engine.toggleSelection(bucket, n1)

        engine.canDownloadSelection(ContentType.ALL) shouldBe true
    }

    @Test
    fun `bulk Download skips the provider whose part is local`() {
        every { manga.containsLocal(setOf(m1, n1)) } returns true
        engine.toggleSelection(bucket, m1)
        engine.toggleSelection(bucket, n1)

        engine.performDownloadAction(ContentType.ALL, DownloadAction.NEXT_1_CHAPTER)

        verify(exactly = 0) { manga.performDownloadAction(any(), any()) }
    }

    @Test
    fun `two entries of one type can merge`() {
        engine.toggleSelection(bucket, m1)
        engine.toggleSelection(bucket, m2)

        engine.canMergeSelection() shouldBe true
    }

    @Test
    fun `one entry alone cannot merge`() {
        engine.toggleSelection(bucket, m1)

        engine.canMergeSelection() shouldBe false
    }

    /** A merge group is per content type, so a mixed gesture would write two groups. */
    @Test
    fun `a manga and a novel cannot merge`() {
        engine.toggleSelection(bucket, m1)
        engine.toggleSelection(bucket, n1)

        engine.canMergeSelection() shouldBe false
    }

    @Test
    fun `nothing merges with series merging off`() {
        val engine = engineOver(listOf(manga, novel), mergingEnabled = false)
        engine.toggleSelection(bucket, m1)
        engine.toggleSelection(bucket, m2)

        engine.canMergeSelection() shouldBe false
    }

    @Test
    fun `a merge the gate refuses reaches no provider`() {
        engine.toggleSelection(bucket, m1)

        engine.mergeSelection(ContentType.ALL)

        verify(exactly = 0) { manga.mergeSelection(any()) }
    }

    @Test
    fun `the hopper's gravity is written library-wide`() {
        val store = EmittingPreferenceStore()

        engineOver(listOf(manga, novel), store = store).setHopperGravity(2)

        ReikaiLibraryPreferences(store).hopperGravity.get() shouldBe 2
    }

    @Test
    fun `selected novels can be exported`() {
        engine.toggleSelection(bucket, n1)

        engine.canExportSelection(ContentType.ALL) shouldBe true
    }

    @Test
    fun `a selection holding a manga cannot be exported`() {
        engine.toggleSelection(bucket, m1)
        engine.toggleSelection(bucket, n1)

        engine.canExportSelection(ContentType.ALL) shouldBe false
    }

    @Test
    fun `local novels alone cannot be exported`() {
        every { novel.containsLocal(setOf(n1)) } returns true
        engine.toggleSelection(bucket, n1)

        engine.canExportSelection(ContentType.ALL) shouldBe false
    }

    private fun providerOf(type: ContentType) = if (type == ContentType.MANGA) manga else novel

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
