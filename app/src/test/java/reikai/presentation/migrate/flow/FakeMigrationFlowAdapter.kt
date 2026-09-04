package reikai.presentation.migrate.flow

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.presentation.migrate.PickMember

/**
 * An adapter whose search always finds a target and whose migrate is recorded. [failFor] makes that
 * entry's commit throw, which is how the failure paths are reached. Shared by both flow routes: the
 * batch list and the single-entry search screen run the same seam, and a second copy of this is how
 * the two came to be tested against different contracts.
 */
class FakeMigrationFlowAdapter(
    private val entries: List<MigrationEntry>,
    private val failFor: Set<EntryId> = emptySet(),
    /** Migrating this entry hangs until the test cancels the batch, so the rows after it in the
     *  batch are left accepted and un-run, which is the state a cancelled batch really produces. */
    private val blockOn: EntryId? = null,
    /** These entries find nothing, the state the hide-unmatched toggle acts on. */
    private val matchless: Set<EntryId> = emptySet(),
    private val tuning: MigrationTuning = MigrationTuning(),
    /** The suggestion's chapter number. Null is the real starting point for a search hit: the count
     *  arrives later, from the peek. */
    private val suggestionLatestChapter: Double? = 2.0,
    /** What the count peek fills in, or null for a peek that answers nothing. */
    private val peekLatestChapter: Double? = null,
) : MigrationFlowAdapter {
    val migrated = mutableListOf<EntryId>()
    val blocked = CompletableDeferred<Unit>()

    /** What the automatic per-row search was asked to run under. */
    val suggestedWith = mutableListOf<MigrationTuning>()

    /** The query strings the manual searches sent to a source. */
    val candidateQueries = mutableListOf<String>()

    override val contentType = ContentType.MANGA
    override val matchStrategy = MatchStrategy.BestTitleMatch

    override suspend fun enabledSources() = listOf(
        MigrationSourceUi("target", "Target", "en", MigrationSourceIcon.NovelUrl(null)),
    )

    override fun savedSelection() = listOf("target")
    override fun persistSelection(keys: List<String>) = Unit
    override fun pinnedKeys(): Set<String> = emptySet()
    override suspend fun mergeGroupMembers(ids: List<Long>): List<PickMember> = emptyList()
    override suspend fun sourceDisplayName(sourceKey: String) = sourceKey
    override fun favorites(sourceKey: String): Flow<List<MigrationFavorite>> = flowOf(emptyList())
    override fun readTuning() = tuning
    override fun persistTuning(tuning: MigrationTuning) = Unit
    override suspend fun loadEntries(ids: List<Long>) = entries.filter { it.id.rawId in ids }

    override suspend fun suggest(
        entry: MigrationEntry,
        sourceKey: String,
        tuning: MigrationTuning,
    ): MigrationCandidate? {
        suggestedWith += tuning
        return candidateFor(entry).takeUnless { entry.id in matchless }
    }

    override suspend fun candidates(entry: MigrationEntry, query: String, sourceKey: String): List<MigrationCandidate> {
        candidateQueries += query
        return listOf(candidateFor(entry))
    }

    override suspend fun resolve(candidate: MigrationCandidate) = ResolvedTarget(candidate, syncedNow = true)
    override suspend fun peekCounts(candidate: MigrationCandidate): MigrationCandidate? =
        peekLatestChapter?.let { candidate.copy(latestChapter = it) }
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
    ) {
        if (entry.id == blockOn) blocked.await()
        if (entry.id in failFor) error("migrate failed for ${entry.id}")
        migrated += entry.id
    }

    private fun candidateFor(entry: MigrationEntry) = MigrationCandidate(
        sourceKey = "target",
        title = "Target for ${entry.title}",
        // The two counts arrive together, as they do from a real source, and the peek is skipped
        // for a candidate that already carries either one.
        chapterCount = suggestionLatestChapter?.let { 2 },
        latestChapter = suggestionLatestChapter,
        key = "target:${entry.id}",
        handle = Any(),
    )
}

/** A migratable entry, with the fields both routes read off it. */
fun migrationEntry(id: Long) = MigrationEntry(
    id = EntryId.Manga(id),
    title = "Entry $id",
    sourceKey = "src",
    sourceName = "Source",
    chapterCount = 1,
    latestChapter = 1.0,
    cover = null,
    payload = Any(),
)
