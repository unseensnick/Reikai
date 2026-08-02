package reikai.presentation.migrate.flow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.presentation.migrate.PickMember

/** [runCatching] that rethrows [CancellationException]: the flow's search/commit coroutines must die
 *  on cancellation instead of reporting a cancelled call as "no match" or a row failure. */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

/**
 * Content-type-neutral data flags for the migration confirm dialog. Both per-type enums carry the
 * same five concepts but on DIFFERENT bit layouts (manga `MigrationFlag`, novel `NovelMigrationFlag`),
 * so adapters map by name, never by bit. Tracks migrate unconditionally on both types and are
 * deliberately not a flag.
 */
enum class MigrationDataFlag {
    CHAPTER,
    CATEGORY,
    CUSTOM_COVER,
    NOTES,
    REMOVE_DOWNLOAD,
}

/** A migration target source. [key] is the neutral id (manga `Long` stringified, novel plugin id).
 *  [icon] is the per-type icon payload (manga domain `Source` for `SourceIcon`, novel `iconUrl`). */
data class MigrationSourceUi(
    val key: String,
    val name: String,
    val lang: String,
    val icon: Any?,
)

/** An entry being migrated, loaded once per flow run. [payload] is the per-type domain model
 *  (`Manga` / `Novel`), consumed only by the owning adapter and the per-type cover mappers. */
data class MigrationEntry(
    val id: EntryId,
    val title: String,
    val sourceKey: String,
    val sourceName: String?,
    val chapterCount: Int?,
    /** Highest known chapter number, the upstream comparison basis for prioritize-by-chapters and
     *  hide-without-updates (sources split/bundle chapters differently, so row count alone lies). */
    val latestChapter: Double? = null,
    /** Adapter-built Coil cover model (`MangaCover` / `NovelCover`) for the row thumbnail. */
    val cover: Any?,
    val payload: Any,
)

/** A candidate target found on one source. [handle] is adapter-owned and round-trips through
 *  [MigrationFlowAdapter.resolve] into a commit-ready target; the shared flow never inspects it. */
data class MigrationCandidate(
    val sourceKey: String,
    val title: String,
    val chapterCount: Int?,
    /** Highest known chapter number; see [MigrationEntry.latestChapter]. */
    val latestChapter: Double? = null,
    val handle: Any,
)

/** One row of the per-source favorites picker, deliberately lighter than [MigrationEntry] (no
 *  chapter count, so listing a large source's favorites costs no per-row query). [cover] is the
 *  adapter-built Coil model; [payload] is the per-type domain model for the details push. */
data class MigrationFavorite(
    val id: EntryId,
    val title: String,
    val cover: Any?,
    val payload: Any,
)

/** The pre-list search options. [extraQuery] is transient per run (matching Mihon, which threads it
 *  as a screen argument); the toggles persist per type. Smart-match options ([deepSearch],
 *  [prioritizeByChapters]) only apply where [MigrationFlowAdapter.supportsSmartMatch]. */
data class MigrationTuning(
    val extraQuery: String? = null,
    val deepSearch: Boolean = false,
    val prioritizeByChapters: Boolean = false,
    val hideUnmatched: Boolean = false,
    val hideWithoutUpdates: Boolean = false,
)

/**
 * The per-type seam of the unified migration flow. Everything above this interface is shared and
 * written once; implementations bottom out at the per-type engines (`MigrateMangaUseCase` /
 * `MigrateNovelUseCase`, the smart-search engines, `searchNovels`) and the per-type prefs, and are
 * the only place the flow touches a content type. See
 * docs/dev/plans/content-layer-migrate-surface.md, "The step machine and its adapter contracts".
 */
interface MigrationFlowAdapter {
    val contentType: ContentType

    /** Whether the smart-match tuning options (deep search, prioritize-by-chapters) apply. */
    val supportsSmartMatch: Boolean

    /** Whether [suggest] fills chapter counts. Hide-without-updates needs suggest-time counts, so the
     *  tuning sheet hides that toggle when this is false (novels resolve counts only at accept). */
    val suggestsChapterCounts: Boolean

    /** One-time readiness work before sources are read (the novel side loads its plugin host here;
     *  without it, entering the flow before the host warms up shows empty sources with no error). */
    suspend fun prepare() {}

    /** Enabled sources only: a disabled source or denied language is never offered as a target. */
    fun enabledSources(): List<MigrationSourceUi>

    fun savedSelection(): List<String>

    fun persistSelection(keys: List<String>)

    /** Pinned source keys, the config screen's selection default when nothing is saved yet. */
    fun pinnedKeys(): Set<String>

    /** Every entry in [ids] expanded to its full merge group, deduped in encounter order. The pick
     *  screen skips itself when the members are exactly the input set (nothing merged). */
    suspend fun mergeGroupMembers(ids: List<Long>): List<PickMember>

    /** The display name for one source key, falling back to the key when the source is gone (the
     *  favorites picker works for an uninstalled source; the stored rows still migrate). */
    fun sourceDisplayName(sourceKey: String): String

    /** The library favorites belonging to one source, title-sorted, for the favorites picker. */
    fun favorites(sourceKey: String): Flow<List<MigrationFavorite>>

    fun readTuning(): MigrationTuning

    fun persistTuning(tuning: MigrationTuning)

    suspend fun loadEntries(ids: List<Long>): List<MigrationEntry>

    /** The best match for [entry] on one source, or null when the source has none. */
    suspend fun suggest(entry: MigrationEntry, sourceKey: String, tuning: MigrationTuning): MigrationCandidate?

    /** The override picker's per-source result list for a user-edited [query]. */
    suspend fun candidates(entry: MigrationEntry, query: String, sourceKey: String): List<MigrationCandidate>

    /** Materialise a picked candidate into a commit-ready target (row inserted, chapters fetched,
     *  count known). Null on failure; [migrate] requires a resolved candidate. */
    suspend fun resolve(candidate: MigrationCandidate): MigrationCandidate?

    /** Wrap an already-stored entry (a duplicate-dialog migrate target) as a resolved, commit-ready
     *  candidate, bypassing search. Null when the row is gone. */
    suspend fun storedCandidate(id: Long): MigrationCandidate?

    fun savedFlags(): Set<MigrationDataFlag>

    /** The flags worth offering for these entries: CHAPTER and CATEGORY always; the rest only when
     *  at least one entry actually has the thing (custom cover, notes, downloads). */
    suspend fun applicableFlags(entries: List<MigrationEntry>): Set<MigrationDataFlag>

    /** Commit one entry onto a [resolve]d target, persisting [flags] to the per-type pref first
     *  (matching both existing dialogs). Throws on failure; the shared model owns retry surfacing. */
    suspend fun migrate(
        entry: MigrationEntry,
        target: MigrationCandidate,
        replace: Boolean,
        flags: Set<MigrationDataFlag>,
    )
}
