package reikai.presentation.migrate.flow

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.presentation.migrate.PickMember

/**
 * The source selection: which sources a migration opens with, and what its order writes leave saved.
 * Both are read by the flow long after this screen is gone, so a wrong answer here is invisible
 * until a search runs on the wrong sources.
 */
class EntryMigrationConfigViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    /** Only the source-selection half of the seam; the rest of the flow has its own fake. */
    private class SourceAdapter(
        private val sources: List<String>,
        private var saved: List<String> = emptyList(),
        private val pinned: Set<String> = emptySet(),
    ) : MigrationFlowAdapter {
        val writes = mutableListOf<List<String>>()

        override val contentType = ContentType.MANGA
        override val matchStrategy = MatchStrategy.BestTitleMatch

        override suspend fun enabledSources() = sources.map {
            MigrationSourceUi(it, it.uppercase(), "en", MigrationSourceIcon.NovelUrl(null))
        }

        override fun savedSelection() = saved

        override fun persistSelection(keys: List<String>) {
            writes += keys
            saved = keys
        }

        override fun pinnedKeys() = pinned
        override suspend fun mergeGroupMembers(ids: List<Long>): List<PickMember> = emptyList()
        override suspend fun sourceDisplayName(sourceKey: String) = sourceKey
        override fun favorites(sourceKey: String): Flow<List<MigrationFavorite>> = flowOf(emptyList())
        override fun readTuning() = MigrationTuning()
        override fun persistTuning(tuning: MigrationTuning) = Unit
        override suspend fun loadEntries(ids: List<Long>): List<MigrationEntry> = emptyList()

        override suspend fun suggest(
            entry: MigrationEntry,
            sourceKey: String,
            tuning: MigrationTuning,
        ): MigrationCandidate? = null

        override suspend fun candidates(
            entry: MigrationEntry,
            query: String,
            sourceKey: String,
        ): List<MigrationCandidate> = emptyList()

        override suspend fun resolve(candidate: MigrationCandidate): ResolvedTarget? = null
        override suspend fun peekCounts(candidate: MigrationCandidate): MigrationCandidate? = null
        override suspend fun storedCandidate(id: Long): MigrationCandidate? = null
        override fun savedFlags(): Set<MigrationDataFlag> = emptySet()
        override fun persistFlags(flags: Set<MigrationDataFlag>) = Unit
        override suspend fun applicableFlags(entries: List<MigrationEntry>): Set<MigrationDataFlag> = emptySet()

        override suspend fun migrate(
            entry: MigrationEntry,
            target: MigrationCandidate,
            replace: Boolean,
            flags: Set<MigrationDataFlag>,
            targetJustSynced: Boolean,
        ) = Unit
    }

    private fun model(adapter: SourceAdapter) = EntryMigrationConfigViewModel(adapter, dispatcher)

    @Test
    fun `with nothing saved and nothing pinned every enabled source is selected`() =
        runTest(dispatcher.scheduler) {
            // The seed stopped at pinned, so this profile opened on an empty selection with Continue
            // hidden, while the search layer would have used every enabled source.
            val model = model(SourceAdapter(sources = listOf("a", "b")))

            advanceUntilIdle()

            model.state.value.selected.map { it.key } shouldBe listOf("a", "b")
        }

    @Test
    fun `pinned sources lead when nothing is saved`() = runTest(dispatcher.scheduler) {
        val model = model(SourceAdapter(sources = listOf("a", "b"), pinned = setOf("b")))

        advanceUntilIdle()

        model.state.value.selected.map { it.key } shouldBe listOf("b")
    }

    @Test
    fun `a saved selection wins over the pinned sources`() = runTest(dispatcher.scheduler) {
        val model = model(
            SourceAdapter(sources = listOf("a", "b"), saved = listOf("a"), pinned = setOf("b")),
        )

        advanceUntilIdle()

        model.state.value.selected.map { it.key } shouldBe listOf("a")
    }

    @Test
    fun `two edits leave the screen's own order saved`() = runTest(dispatcher.scheduler) {
        // What the writes produce, not the order they land in: one test scheduler runs them in
        // sequence, so the race the serialization fixes cannot be reproduced here.
        val adapter = SourceAdapter(sources = listOf("a", "b", "c"), saved = listOf("a", "b", "c"))
        val model = model(adapter)
        advanceUntilIdle()

        model.toggleSelection("a")
        model.toggleSelection("b")
        advanceUntilIdle()

        model.state.value.selected.map { it.key } shouldBe listOf("c")
        adapter.writes.last() shouldBe listOf("c")
    }
}
