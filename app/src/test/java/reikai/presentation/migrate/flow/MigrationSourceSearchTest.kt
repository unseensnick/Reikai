package reikai.presentation.migrate.flow

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.novel.source.NovelExtensionFormat

/**
 * What the single-entry search screen and the batch list's override picker share: the strips they
 * publish, seeded loading and filled per source, and the refusal of a chapterless pick.
 */
class MigrationSourceSearchTest {

    private fun source(key: String) =
        MigrationSourceUi(key, key.uppercase(), "en", MigrationSourceIcon.NovelUrl(null), NovelExtensionFormat.APK)

    @Test
    fun `a source's strip opens loading, carrying the source's heading`() {
        source("a").loadingStrip() shouldBe SourceStrip("a", "A", "en", NovelExtensionFormat.APK, StripResult.Loading)
    }

    @Test
    fun `a landed result fills only its own source's strip`() {
        val strips = listOf(source("a").loadingStrip(), source("b").loadingStrip())

        strips.withResult("b", StripResult.Failed("down")).map { it.result } shouldBe
            listOf(StripResult.Loading, StripResult.Failed("down"))
    }

    @Test
    fun `a pick that resolves without chapters is refused`() = runTest {
        val adapter = FakeMigrationFlowAdapter(listOf(migrationEntry(1)), suggestionLatestChapter = null)
        val pick = adapter.candidates(migrationEntry(1), "Entry 1", "target").single()

        adapter.resolvePick(pick) shouldBe PendingPick.Rejected(PickOutcome.NoChapters)
    }

    @Test
    fun `a pick that cannot be resolved is refused`() = runTest {
        val adapter = FakeMigrationFlowAdapter(listOf(migrationEntry(1)), onResolve = { null })
        val pick = adapter.candidates(migrationEntry(1), "Entry 1", "target").single()

        adapter.resolvePick(pick) shouldBe PendingPick.Rejected(PickOutcome.NoChapters)
    }

    @Test
    fun `a pick that resolves with chapters is ready, as resolved`() = runTest {
        val adapter = FakeMigrationFlowAdapter(
            listOf(migrationEntry(1)),
            onResolve = { ResolvedTarget(it.copy(chapterCount = 7), syncedNow = true) },
        )
        val pick = adapter.candidates(migrationEntry(1), "Entry 1", "target").single()

        adapter.resolvePick(pick) shouldBe PendingPick.Ready(pick.copy(chapterCount = 7))
    }
}
