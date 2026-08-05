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
 * The shared read side of a merged entry's details screen: the group ids, the selected-source chip, the
 * membership observer that keeps them live, and the source-switcher chips. Both details models compose this,
 * so the read wiring that drifted before (the group-refresh observer existed novel-side and was missing
 * manga-side, so a manga chip only appeared after reopening) is written once and can't diverge. Mirrors the
 * write-side [EntryMergeActionHost].
 *
 * The two per-type differences are injected, exactly as the action host injects its two:
 * - [anchorChanges] emits the current anchor id whenever the anchor or group membership changes (manga: a
 *   constant re-emitted on every membership change; novel: the url+source lookup combined with membership,
 *   whose closure also updates the novel model's own anchor field).
 * - [resolveSources] maps the grouped ids to the switcher chips (manga: synchronous `getOrStub`; novel:
 *   async plugin-load plus the sibling-source map its reader routing needs, populated inside the closure).
 *   It owns the not-merged case too (size <= 1 returns empty), so the novel side can clear its sibling map.
 *
 * [observe] is called from each model's init once its own fields are set (the injected closures capture
 * model state), never from this class's constructor, to avoid touching not-yet-initialized fields.
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
     * [selected] is only ever an id present in [ids]. Keeping them apart made that impossible to hold:
     * membership and selection were two flows, so a consumer combining them saw the pair disagree for
     * one emission whenever a member left, and the two details models each papered over it their own
     * way. Migrating a selected source out of the group crashed the manga chapter pipeline on an
     * unguarded lookup, and silently rendered the migrated-away source on novels.
     *
     * [ids] compares by identity ([LongArray] has no structural equals), which the re-aggregate paths
     * rely on: writing a `copyOf()` re-emits without changing membership.
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
