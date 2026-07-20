package reikai.presentation.details

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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

    /** Group ids (this entry + grouped siblings); size <= 1 when not merged. Drives the chapter combine. */
    val relatedIds = MutableStateFlow(initialIds)

    /** The grouped source chip in view; null = the unified ("All") list. */
    val selectedSource = MutableStateFlow<Long?>(null)

    private val _chips = MutableStateFlow<List<EntryMergeSource>>(emptyList())

    /** Source-switcher chips for the current group; empty when not merged. */
    val chips: StateFlow<List<EntryMergeSource>> = _chips.asStateFlow()

    /**
     * Start the two collectors: recompute [relatedIds] when the anchor or group membership changes, and
     * rebuild [chips] whenever [relatedIds] changes.
     */
    fun observe(scope: CoroutineScope) {
        scope.launchIO {
            anchorChanges.collectLatest { relatedIds.value = mergeManager.computeRelatedIds(it) }
        }
        scope.launchIO {
            relatedIds.collectLatest { _chips.value = resolveSources(it) }
        }
    }

    /**
     * Resolve the group + chips once for the first-render seed (manga's eager load), setting [relatedIds]
     * and returning the chips so the caller seeds them into the initial state atomically, before the
     * reactive collectors fire.
     */
    suspend fun seed(anchorId: Long): List<EntryMergeSource> {
        val ids = mergeManager.computeRelatedIds(anchorId)
        relatedIds.value = ids
        return resolveSources(ids)
    }
}
