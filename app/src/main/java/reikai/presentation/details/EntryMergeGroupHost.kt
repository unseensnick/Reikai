package reikai.presentation.details

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import reikai.domain.merge.EntryMergeManager
import tachiyomi.core.common.util.lang.launchIO

/**
 * The shared read side of a merged entry's details screen: the group ids, the selected-source chip,
 * the membership observer keeping them live, and the source-switcher chips. Mirrors the write-side
 * [EntryMergeActionHost]. Two per-type differences are injected: [anchorChanges] emits the anchor id
 * whenever the anchor or membership changes, and [resolveSources] maps the grouped ids to chips,
 * owning the not-merged case so the novel side can clear its sibling map. [observe] is called from
 * each model's init once its fields are set, never here, since the closures capture model state.
 */
class EntryMergeGroupHost(
    private val mergeManager: EntryMergeManager,
    initialIds: LongArray,
    private val anchorChanges: Flow<Long>,
    private val resolveSources: suspend (LongArray) -> List<EntryMergeSource>,
) {

    /**
     * The group and the chip chosen inside it, as ONE cell.
     *
     * [selected] is only ever an id present in [ids], which two separate flows could not hold: a
     * consumer combining them saw the pair disagree for one emission whenever a member left. Migrating
     * a selected source out crashed the manga chapter pipeline on an unguarded lookup and silently
     * rendered the migrated-away source on novels. [ids] compares by IDENTITY ([LongArray] has no
     * structural equals), which the re-aggregate paths rely on: writing a `copyOf()` re-emits without
     * changing membership.
     */
    data class GroupState(val ids: LongArray, val selected: Long?)

    private val _state = MutableStateFlow(GroupState(initialIds, selected = null))

    /** The single truth both details models read; see [GroupState]. */
    val state: StateFlow<GroupState> = _state.asStateFlow()

    /** Group ids (this entry + grouped siblings); size <= 1 when not merged. */
    val relatedIds: LongArray get() = _state.value.ids

    /** The grouped source chip in view; null = the unified ("All") list. */
    val selectedSource: Long? get() = _state.value.selected

    /** [selectedSource] as a stream, for the readers that only track the chip. */
    val selectedSourceChanges: Flow<Long?> = _state.map { it.selected }.distinctUntilChanged()

    private val _chips = MutableStateFlow<List<EntryMergeSource>>(emptyList())

    /** Source-switcher chips for the current group; empty when not merged. */
    val chips: StateFlow<List<EntryMergeSource>> = _chips.asStateFlow()

    /**
     * Start the two collectors: recompute the group when the anchor or group membership changes, and
     * rebuild [chips] whenever the membership changes.
     */
    fun observe(scope: CoroutineScope) {
        scope.launchIO {
            anchorChanges.collectLatest { setRelated(mergeManager.computeRelatedIds(it)) }
        }
        scope.launchIO {
            _state.map { it.ids }.distinctUntilChanged().collectLatest { _chips.value = resolveSources(it) }
        }
    }

    /**
     * The one way membership changes, so the selection invariant holds by construction rather than by
     * each caller remembering to reset the chip. A selection that survives the change is kept:
     * splitting one sibling away should not knock the user off the chip they were reading.
     */
    fun setRelated(ids: LongArray) {
        _state.value = GroupState(ids, _state.value.selected?.takeIf { it in ids })
    }

    /** Show one grouped source's chapters, or pass null for the unified ("All") list. An id that is
     *  not in the group is refused rather than stored, for the same reason [setRelated] prunes. */
    fun selectSource(entryId: Long?) {
        _state.update { it.copy(selected = entryId?.takeIf { id -> id in it.ids }) }
    }

    /**
     * Re-read [anchorId]'s group from storage and publish it.
     *
     * The one way a caller that just changed the grouping updates this cell. Callers used to state the
     * new membership from whatever their operation returned, and a split returns the SURVIVORS: split
     * the anchor's own source out of a three-member group and the cell became the two OTHER entries.
     * Reading the same source of truth [observe] reads means an optimistic update cannot disagree with
     * the membership emission that follows it.
     */
    suspend fun refresh(anchorId: Long) = setRelated(mergeManager.computeRelatedIds(anchorId))

    /**
     * Resolve the group + chips once for the first-render seed (manga's eager load), setting [relatedIds]
     * and returning the chips so the caller seeds them into the initial state atomically, before the
     * reactive collectors fire.
     */
    suspend fun seed(anchorId: Long): List<EntryMergeSource> {
        val ids = mergeManager.computeRelatedIds(anchorId)
        setRelated(ids)
        return resolveSources(ids)
    }
}
