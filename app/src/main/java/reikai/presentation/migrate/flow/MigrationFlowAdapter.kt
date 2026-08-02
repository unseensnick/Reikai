package reikai.presentation.migrate.flow

import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType

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
    val handle: Any,
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

    /** Enabled sources only: a disabled source or denied language is never offered as a target. */
    fun enabledSources(): List<MigrationSourceUi>

    fun savedSelection(): List<String>

    fun persistSelection(keys: List<String>)

    /** Pinned source keys, the config screen's selection default when nothing is saved yet. */
    fun pinnedKeys(): Set<String>

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
