package reikai.presentation.recents

import cafe.adriel.voyager.core.screen.Screen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.merge.MergeManager
import tachiyomi.core.common.preference.Preference

/**
 * One lane's rows plus whether they are real yet. [loaded] cannot be recovered downstream: a lane
 * mapped off a model's state emits immediately with an empty list while the query is still running,
 * because the state flow is seeded rather than opened by its first emission. Treating that emission as
 * data is what makes a feed announce itself empty a tick after it opens.
 */
data class RecentsLaneRows(val items: List<RecentsItem>, val loaded: Boolean) {
    companion object {
        val Loading = RecentsLaneRows(emptyList(), loaded = false)
    }
}

/**
 * One derivation for both adapters: this type's group memberships keyed neutrally, and empty while
 * merging is off, so a feed collapses exactly when every other surface would.
 */
internal fun MergeManager.membershipFlow(
    mergingEnabled: Preference<Boolean>,
    entryId: (Long) -> EntryId,
): Flow<Map<EntryId, Long>> =
    combine(mergingEnabled.changes(), membershipChanges()) { enabled, memberships ->
        if (enabled) memberships.mapKeys { entryId(it.key) } else emptyMap()
    }

/** A query-backed lane: its first emission is real data, so it only needs a value to start from. */
internal fun Flow<List<RecentsItem>>.asLane(): Flow<RecentsLaneRows> =
    map { RecentsLaneRows(it, loaded = true) }.onStart { emit(RecentsLaneRows.Loading) }

/**
 * One content type's half of the recents surface: its three lane feeds plus the verbs in
 * [RecentsBehavior]. The lanes are separate flows because an engine collects only the lanes its modes
 * render, which is what stops the two-tab shape running every query twice.
 */
interface RecentsProvider : RecentsBehavior {
    val contentType: ContentType

    /** Entries with reading history, newest read first, one row per entry. */
    val readLane: Flow<RecentsLaneRows>

    /** Chapters fetched after the entry was added, newest first. */
    val updatedLane: Flow<RecentsLaneRows>

    /** Entries recently added to the library, newest first. */
    val addedLane: Flow<RecentsLaneRows>

    /**
     * This type's entries that still have an unread chapter, so the combined modes can drop a row
     * with nothing left to read. One set per emission, never a question per row: the feed re-emits on
     * every library change, and [targetChapter] already pays a query per rendered row by design.
     */
    val unreadEntries: Flow<Set<EntryId>>

    /** When this type's library last finished updating. Each type has its own update job and key. */
    val lastUpdated: Flow<Long>

    /**
     * Whether this type's library update is running now, so a refreshing state ends when the job does.
     * A data flow, so it belongs here rather than on the verb seam above; it reports a scheduled update
     * as well as a pulled one, which is what the job itself already treats as "already running".
     */
    val updating: Flow<Boolean>

    /**
     * This type's entries that belong to a merge group, by group id, so a feed can show one row for a
     * series merged across sources. Empty while the user has series merging off, matching every other
     * path that resolves a group. Group ids are unique across both content types, so the two providers'
     * maps combine without collision.
     */
    val membership: Flow<Map<EntryId, Long>>

    /**
     * Everything [item] draws, read out of this type's own payload. Answered per rendered row rather
     * than baked into the item, matching the other two accessors here, so an assembly stays cheap and
     * a row that never reaches the screen is never projected.
     */
    fun rowUi(item: RecentsItem): RecentsRowUi

    /**
     * This row's download state, null where the row has no chapter to download. Separate from [rowUi]
     * because these are callbacks the renderer polls, not values, and folding them into an immutable
     * projection would make its equality meaningless.
     */
    fun downloadUi(item: RecentsItem): RecentsDownloadUi?

    /**
     * The title this row displays, which is what a search matches. Every model already writes the
     * user's custom title into the row it emits, so a renamed entry is findable by the name on screen
     * without the engine ever unwrapping a payload.
     */
    fun title(item: RecentsItem): String = rowUi(item).title

    /**
     * The details screen for [entry], resolved rather than constructed by the caller: a novel screen is
     * keyed by source and url, so the id a row carries is not enough to build one. Null when the entry
     * has gone. Suspend for that lookup; the manga side answers without touching the database.
     */
    suspend fun detailsScreen(entry: EntryId): Screen?

    fun lane(kind: RecentsLaneKind): Flow<RecentsLaneRows> = when (kind) {
        RecentsLaneKind.READ -> readLane
        RecentsLaneKind.UPDATED -> updatedLane
        RecentsLaneKind.ADDED -> addedLane
    }

    /**
     * The chapter a tap on [item] opens, resolved per lane: resume where you were on read, the first
     * unread of the burst on updated, the first unread on added. Null when nothing is left to open.
     * Which lanes resolve over the merge group rather than in-source is what [sourceScoped] pairs to.
     *
     * Suspend and called per rendered row on purpose. Resolving at assembly would put one chapter
     * query per row on every emission, which on a five-hundred-row feed is the cost this surface is
     * being built to avoid.
     */
    suspend fun targetChapter(item: RecentsItem): ChapterRef?

    /**
     * The same chapter [targetChapter] resolves, projected into everything a row draws about it, or
     * null where nothing is left to open and where the chapter or its entry has gone. The ref names
     * the entry that owns the chapter, which a merged row can resolve to a sibling of its own, while
     * [targetChapter] stamps the row's entry on the same id; only the verb dispatch reads that, and it
     * cares about content type alone. Answered per rendered row, at the same cost as [targetChapter].
     */
    suspend fun targetRow(item: RecentsItem): RecentsTargetRow?

    /**
     * This type's most recently read entry, as a read-lane item, or null where the surface renders no
     * read lane. Deliberately unfiltered: a search or a category filter narrows what you are looking
     * through, and resuming is not about the list.
     */
    suspend fun latestRead(): RecentsItem?

    /**
     * How a tap on [item] opens whatever [targetChapter] resolved, or null when nothing is left to
     * open. The provider builds it because only it knows which reader its content type has.
     */
    suspend fun open(item: RecentsItem): RecentsOpen?
}
