package reikai.presentation.browse

import eu.kanade.tachiyomi.ui.manga.MangaScreen
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.novel.host.NovelItem
import reikai.presentation.novel.browse.NovelAddFlow
import reikai.presentation.novel.details.NovelScreen
import tachiyomi.domain.manga.model.Manga

/**
 * The long-press add flow, pinned once for both content types over the real adders. The shared
 * dialogs dismiss before they confirm, so what a verb acts on has to outlive the dismiss; and a list
 * can be drawn before an add made elsewhere, so the decision reads the stored row.
 * Background: docs/dev/plans/content-layer-add-flow.md.
 */
class EntryAddFlowConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a long press on an entry added since it was listed offers removal`(probe: AddFlowProbe) = runTest {
        probe.storeEntry(inLibrary = true)

        probe.pressEntry()

        probe.flow.dialog.value.shouldBeInstanceOf<EntryAddDialog.Remove>()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a confirm after the dialog is dismissed still acts`(probe: AddFlowProbe) = runTest {
        probe.storeEntry(inLibrary = true)
        probe.pressEntry()

        probe.flow.dismiss()
        probe.act { flow.confirmRemove() }

        probe.entryInLibrary() shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `adding anyway asks for categories, then files there`(probe: AddFlowProbe) = runTest {
        probe.storeDuplicate()
        probe.storeEntry(inLibrary = false)
        probe.pressEntry()

        probe.act { flow.confirmAddDuplicate() }
        probe.act { flow.confirmCategories(listOf(PICKED_CATEGORY)) }

        probe.entryCategories() shouldBe listOf(PICKED_CATEGORY)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a group add with no default asks for categories, then files there`(probe: AddFlowProbe) = runTest {
        val duplicate = probe.storeDuplicate()
        probe.storeEntry(inLibrary = false)
        probe.pressEntry()

        probe.act { flow.addToGroup(listOf(duplicate)) }
        probe.act { flow.confirmCategories(listOf(PICKED_CATEGORY)) }

        probe.entryCategories() shouldBe listOf(PICKED_CATEGORY)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `migrating from a duplicate asks to move it onto the pressed entry`(probe: AddFlowProbe) = runTest {
        val duplicate = probe.storeDuplicate()
        probe.storeEntry(inLibrary = false)
        probe.pressEntry()

        probe.act { flow.startMigrate(duplicate) }

        probe.flow.dialog.value shouldBe EntryAddDialog.Migrate(currentId = duplicate, targetId = probe.entryId())
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a new long press replaces what the last one raised`(probe: AddFlowProbe) = runTest {
        probe.storeEntry(inLibrary = true)
        probe.storeOther(inLibrary = true)
        probe.pressEntry()
        probe.pressOther()

        probe.flow.dismiss()
        probe.act { flow.confirmRemove() }

        probe.otherInLibrary() shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a duplicate card opens that duplicate`(probe: AddFlowProbe) = runTest {
        val duplicate = probe.storeDuplicate()
        probe.storeEntry(inLibrary = false)
        probe.pressEntry()

        probe.openedDuplicate(duplicate) shouldBe probe.expectedDuplicate
    }

    companion object {
        const val PICKED_CATEGORY = 3L

        @JvmStatic
        fun probes() = listOf(MangaAddFlowProbe(), NovelAddFlowProbe())
    }
}

/**
 * One content type's flow over its fake library, with one user category and "always ask" as the
 * default, so every add that has no group to follow raises the picker.
 */
abstract class AddFlowProbe {
    private val job = SupervisorJob()
    protected val scope = CoroutineScope(job)

    abstract val flow: EntryAddFlow<*>

    abstract fun storeEntry(inLibrary: Boolean)
    abstract fun storeOther(inLibrary: Boolean)

    /** Stores a library entry the duplicate lookup lists, answering its id. */
    abstract fun storeDuplicate(): Long

    /** Presses the entry as a list drew it before anything was added: out of the library. */
    protected abstract fun longPressEntry()
    protected abstract fun longPressOther()

    abstract fun entryInLibrary(): Boolean
    abstract fun otherInLibrary(): Boolean
    abstract fun entryId(): Long
    abstract fun entryCategories(): List<Long>?

    /** What the duplicate card's screen addresses, and what it should. */
    abstract fun openedDuplicate(duplicateId: Long): Any?
    abstract val expectedDuplicate: Any

    suspend fun pressEntry() = act { longPressEntry() }
    suspend fun pressOther() = act { longPressOther() }

    /** Runs [verb], then waits for the one step it launched. */
    suspend fun act(verb: AddFlowProbe.() -> Unit) {
        verb()
        job.children.toList().joinAll()
    }
}

class MangaAddFlowProbe : AddFlowProbe() {
    private val library = FakeMangaLibrary(listOf(libraryCategory(EntryAddFlowConformanceTest.PICKED_CATEGORY)))
    override val flow = MangaAddFlow(library.adder, scope)

    override fun toString() = "manga"

    private fun listed(id: Long) =
        Manga.create().copy(id = id, url = "/$id", source = FakeMangaLibrary.SOURCE_ID, title = "entry $id")

    override fun storeEntry(inLibrary: Boolean) {
        library.insert(listed(ENTRY).copy(favoriteAt = 100L.takeIf { inLibrary }))
    }

    override fun storeOther(inLibrary: Boolean) {
        library.insert(listed(OTHER).copy(favoriteAt = 100L.takeIf { inLibrary }))
    }

    override fun storeDuplicate(): Long {
        library.put(DUPLICATE, favorite = true)
        library.duplicateIds += DUPLICATE
        return DUPLICATE
    }

    override fun longPressEntry() = flow.onLongClick(listed(ENTRY))
    override fun longPressOther() = flow.onLongClick(listed(OTHER))

    override fun entryInLibrary() = library.rows[ENTRY]?.favorite == true
    override fun otherInLibrary() = library.rows[OTHER]?.favorite == true
    override fun entryId() = ENTRY
    override fun entryCategories() = library.filed[ENTRY]

    override fun openedDuplicate(duplicateId: Long) = (flow.duplicateScreen(duplicateId) as? MangaScreen)?.mangaId
    override val expectedDuplicate: Any = DUPLICATE

    private companion object {
        const val ENTRY = 1L
        const val OTHER = 2L
        const val DUPLICATE = 10L
    }
}

class NovelAddFlowProbe : AddFlowProbe() {
    private val library = FakeNovelLibrary(listOf(libraryCategory(EntryAddFlowConformanceTest.PICKED_CATEGORY)))
    override val flow = NovelAddFlow(library.adder, scope)

    override fun toString() = "novel"

    private val entry = NovelItem(name = "entry", path = "/entry", cover = null)
    private val other = NovelItem(name = "other", path = "/other", cover = null)

    override fun storeEntry(inLibrary: Boolean) {
        library.put(ENTRY, entry.path, favorite = inLibrary)
    }

    override fun storeOther(inLibrary: Boolean) {
        library.put(OTHER, other.path, favorite = inLibrary)
    }

    override fun storeDuplicate(): Long {
        library.put(DUPLICATE, DUPLICATE_PATH, favorite = true)
        library.duplicateIds += DUPLICATE
        return DUPLICATE
    }

    override fun longPressEntry() = flow.onLongClick(entry, FakeNovelLibrary.SOURCE_ID)
    override fun longPressOther() = flow.onLongClick(other, FakeNovelLibrary.SOURCE_ID)

    private fun stored(item: NovelItem) = library.rows.values.firstOrNull { it.url == item.path }

    override fun entryInLibrary() = stored(entry)?.favorite == true
    override fun otherInLibrary() = stored(other)?.favorite == true
    override fun entryId() = stored(entry)!!.id
    override fun entryCategories() = library.filed[entryId()]

    override fun openedDuplicate(duplicateId: Long) = (flow.duplicateScreen(duplicateId) as? NovelScreen)?.novelUrl
    override val expectedDuplicate: Any = DUPLICATE_PATH

    private companion object {
        const val ENTRY = 1L
        const val OTHER = 2L
        const val DUPLICATE = 10L
        const val DUPLICATE_PATH = "/duplicate"
    }
}
