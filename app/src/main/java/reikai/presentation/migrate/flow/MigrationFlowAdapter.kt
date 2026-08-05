package reikai.presentation.migrate.flow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.presentation.migrate.PickMember
import tachiyomi.domain.source.model.Source
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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

/** A migration target source. [key] is the neutral id (manga `Long` stringified, novel plugin id). */
data class MigrationSourceUi(
    val key: String,
    val name: String,
    val lang: String,
    val icon: MigrationSourceIcon,
)

/** The per-type icon payload: a typed slot, so shared UI renders by case instead of downcasting. */
sealed interface MigrationSourceIcon {
    data class MangaSource(val source: Source) : MigrationSourceIcon
    data class NovelUrl(val iconUrl: String?) : MigrationSourceIcon
}

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

/**
 * A candidate target found on one source.
 *
 * [handle] is adapter-owned and round-trips through [MigrationFlowAdapter.resolve] into a
 * commit-ready target; the shared flow never inspects it, which is why [cover] and [key] are built
 * by the adapter instead of downcast in UI code.
 */
data class MigrationCandidate(
    val sourceKey: String,
    val title: String,
    val chapterCount: Int?,
    /** Highest known chapter number; see [MigrationEntry.latestChapter]. */
    val latestChapter: Double? = null,
    /** Stable identity within a source (url / path), the lazy-list key. Titles are not unique. */
    val key: String,
    /** Adapter-built Coil cover model for the candidate cell. */
    val cover: Any? = null,
    /**
     * This candidate is already a library entry. Adapter-filled, like [cover] and [key], because
     * favourite-ness lives in the per-type row the UI must not downcast to. Marking only: a target
     * already in the library is a legitimate pick (that is the replace case), so nothing gates on it.
     */
    val inLibrary: Boolean = false,
    /**
     * Adapter-owned, and the only place resolved-ness lives: whether a commit still owes this
     * candidate a materialising [MigrationFlowAdapter.resolve] is a property of the handle, not of
     * the shared model. It used to be a Boolean here whose meaning differed per adapter, which the
     * surface's standing rules forbid. The novel handle answers it with its stored row; manga
     * candidates are stored from search time, so its resolve re-checks chapters regardless.
     */
    val handle: Any,
)

/**
 * What [MigrationFlowAdapter.resolve] produced: the commit-ready [candidate], and whether this call
 * actually pulled the target's chapters from its source.
 *
 * [syncedNow] is returned rather than stored on the candidate on purpose. It is only true of the
 * call that produced it, so a commit can skip a refresh it just performed, while a candidate that
 * has been sitting on a row since an earlier resolve carries no claim about freshness at all.
 */
data class ResolvedTarget(
    val candidate: MigrationCandidate,
    val syncedNow: Boolean,
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

/**
 * The pre-list search options. [extraQuery] is transient per run (matching Mihon, which threads it
 * as a screen argument); the toggles persist per type. Smart-match options ([deepSearch],
 * [prioritizeByChapters]) only apply under [MatchStrategy.Smart].
 *
 * Settled on the config screen before the list exists, and read once from there, so no search can
 * have its options changed underneath it.
 */
data class MigrationTuning(
    val extraQuery: String? = null,
    val deepSearch: Boolean = false,
    val prioritizeByChapters: Boolean = false,
    val hideUnmatched: Boolean = false,
    val hideWithoutUpdates: Boolean = false,
) {
    /**
     * This tuning with the options [strategy] cannot express dropped.
     *
     * [deepSearch] and [prioritizeByChapters] run on the smart-search engines, so they mean nothing
     * under [MatchStrategy.BestTitleMatch]. They stayed plain Booleans on the shared model while the
     * only thing keeping them honest was the sheet hiding their checkboxes; a value set any other way
     * was accepted, persisted nowhere, and read back as false, having already triggered a full row
     * rebuild and re-search on the way through. Normalising here means the model compares and stores
     * what the type can actually hold.
     */
    fun normalizedFor(strategy: MatchStrategy): MigrationTuning = when (strategy) {
        MatchStrategy.Smart -> this
        MatchStrategy.BestTitleMatch -> copy(deepSearch = false, prioritizeByChapters = false)
    }
}

/**
 * The per-type seam of the unified migration flow. Everything above this interface is shared and
 * written once; implementations bottom out at the per-type engines (`MigrateMangaUseCase` /
 * `MigrateNovelUseCase`, the smart-search engines, `searchNovels`) and the per-type prefs, and are
 * the only place the flow touches a content type. Design record:
 * docs/dev/plans/content-layer-migrate-surface.md.
 *
 * **Fetch timing is part of the contract.** [resolve] is the expensive one and the only one a commit
 * depends on having run: it puts the target's full chapter list on the row that will be migrated
 * onto, and it is slow and cancellable. [suggest] enriches the source's winning match alone and
 * [peekCounts] fetches counts for one already-chosen candidate, so both are bounded per row.
 * [candidates] runs one search page. Every other method is local and callers may treat it as free.
 *
 * Storing is NOT the distinction: on the manga side `suggest` and `candidates` also insert rows (via
 * `networkToLocalManga`), and `suggest` syncs chapters. What only [resolve] guarantees is that the
 * candidate a commit is handed is materialised and populated.
 */
interface MigrationFlowAdapter {
    val contentType: ContentType

    /** How this type finds a target: a typed slot, not a capability boolean. */
    val matchStrategy: MatchStrategy

    /** One-time readiness work before sources are read (the novel side loads its plugin host here;
     *  without it, entering the flow before the host warms up shows empty sources with no error).
     *  Must not swallow cancellation: use [runCatchingCancellable], never bare `runCatching`. */
    suspend fun prepare() {}

    /** Enabled sources only: a disabled source or denied language is never offered as a target. */
    fun enabledSources(): List<MigrationSourceUi>

    fun savedSelection(): List<String>

    /** Persist the ordered target selection. A key the adapter cannot parse is a caller bug, so
     *  implementations throw rather than silently shrinking the saved set. */
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

    /** The best match for [entry] on one source, or null when the source has none. Enriches the
     *  winning match only: enriching every probed source multiplies requests per row, the
     *  rate-limit risk the enhanced search options warn about. */
    suspend fun suggest(entry: MigrationEntry, sourceKey: String, tuning: MigrationTuning): MigrationCandidate?

    /** The override picker's per-source result list for a user-edited [query]. Search-page data
     *  only: no detail or chapter fetch, so counts stay null until the pick is resolved. */
    suspend fun candidates(entry: MigrationEntry, query: String, sourceKey: String): List<MigrationCandidate>

    /**
     * Materialise a picked candidate into a commit-ready target (row inserted, chapters fetched,
     * count known), returning it with `resolved = true`. Fails as null OR by throwing (the novel
     * side lets its refresh throw); callers must catch as well as null-check. [migrate] requires a
     * resolved candidate.
     *
     * Idempotent, which the commit path relies on: it resolves unconditionally
     * (a bulk-accepted row still carries an unresolved suggestion), so resolving an already-resolved
     * candidate must be cheap. Implementations report through [ResolvedTarget.syncedNow] whether
     * this call fetched, which is what lets the commit avoid a second identical fetch.
     */
    suspend fun resolve(candidate: MigrationCandidate): ResolvedTarget?

    /**
     * Best-effort chapter counts for a candidate, so the row can show "584 -> 601" instead of
     * unknown. Display-only: the flow never relies on the result being commit-ready (a side where
     * that comes free may return one), and null (couldn't fetch, or a count would be a lie, e.g. a
     * paged novel source's first page) leaves the row reading unknown. Called for found suggestions
     * and for accepted targets; the caller bounds the concurrency, so one fetch per call is fine.
     */
    suspend fun peekCounts(candidate: MigrationCandidate): MigrationCandidate?

    /** Wrap an already-stored entry (a duplicate-dialog migrate target) as a resolved, commit-ready
     *  candidate, bypassing search. Null when the row is gone. */
    suspend fun storedCandidate(id: Long): MigrationCandidate?

    fun savedFlags(): Set<MigrationDataFlag>

    /** Persist the chosen flag set, once, when the user confirms. Never called per row: [migrate]
     *  takes its flags as a value, so a batch cannot rewrite the pref once per row and two
     *  concurrent commits cannot swap each other's flags. */
    fun persistFlags(flags: Set<MigrationDataFlag>)

    /** The flags worth offering for these entries: CHAPTER and CATEGORY always; the rest only when
     *  at least one entry actually has the thing (custom cover, notes, downloads). */
    suspend fun applicableFlags(entries: List<MigrationEntry>): Set<MigrationDataFlag>

    /**
     * Commit one entry onto a [resolve]d target with exactly [flags]. Writes no preferences.
     * Throws on failure; the shared model owns retry surfacing.
     *
     * Both engines re-fetch the target's chapters before carrying read state, so that a migration
     * works from any add path. [targetJustSynced] says the caller's [resolve] already did that fetch
     * moments ago, so repeating it would only cost the source another request; pass it through and
     * skip. It describes this call alone, never a remembered freshness window.
     */
    suspend fun migrate(
        entry: MigrationEntry,
        target: MigrationCandidate,
        replace: Boolean,
        flags: Set<MigrationDataFlag>,
        targetJustSynced: Boolean,
    )
}

/**
 * How a content type matches an entry to a target.
 *
 * A typed slot rather than a `supportsSmartMatch` Boolean: the two tuning options that ride on the
 * smart-search engines are meaningless for a type that has none, and an exhaustive `when` says so at
 * every call site instead of an AND that is easy to forget.
 */
sealed interface MatchStrategy {
    /** The source's best title match, with no options on top (the novel plugin sources). */
    data object BestTitleMatch : MatchStrategy

    /** Mihon's smart-search engines: deep search and prioritize-by-chapters apply. */
    data object Smart : MatchStrategy
}

/**
 * The adapter for one content type.
 *
 * Exhaustive on purpose. [ContentType.ALL] is a live value on the library and browse chips, and an
 * `else` branch would quietly hand novel machinery a list of manga ids rather than failing where the
 * mistake was made. The flow always runs on exactly one content type.
 */
fun migrationAdapterFor(contentType: ContentType): MigrationFlowAdapter = when (contentType) {
    ContentType.MANGA -> Injekt.get<MangaMigrationFlowAdapter>()
    ContentType.NOVELS -> Injekt.get<NovelMigrationFlowAdapter>()
    ContentType.ALL -> error("The migration flow runs on one content type; ALL has no adapter")
}
