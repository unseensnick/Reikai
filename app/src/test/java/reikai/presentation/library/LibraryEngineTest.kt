package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import reikai.domain.category.CATEGORY_HIDDEN_MASK
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga

class LibraryEngineTest {

    private fun provider(type: ContentType, rows: List<LibraryItem> = emptyList()): LibraryProvider {
        val provider = mockk<LibraryProvider>(relaxed = true)
        every { provider.contentType } returns type
        every { provider.rows } returns flowOf(rows)
        every { provider.state } returns MutableStateFlow(screenState)
        every { provider.overlaid(any()) } answers { firstArg() }
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
    ): LibraryEngine {
        val store = EmittingPreferenceStore()
        val repository = mockk<CategoryRepository>(relaxed = true)
        every { repository.getUnfilteredAsFlow() } returns flowOf(categories)
        return LibraryEngine(
            providers = providers,
            reikaiLibraryPreferences = ReikaiLibraryPreferences(store),
            libraryPreferences = LibraryPreferences(store),
            categoryRepository = repository,
        )
    }

    private val screenState = LibraryScreenState(
        isLoading = false,
        isLibraryEmpty = false,
        searchQuery = null,
        hasActiveFilters = false,
        activeCategoryIndex = 0,
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

    @Test
    fun `opening a dialog keeps the selection until the dialog resolves`() {
        engine.toggleSelection(bucket, m1)
        engine.openDeleteDialog(ContentType.MANGA)
        engine.selection.value shouldContainExactly setOf(m1)
    }
}
