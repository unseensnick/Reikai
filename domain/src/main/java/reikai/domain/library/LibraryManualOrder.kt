package reikai.domain.library

import kotlinx.coroutines.flow.Flow

/** One entry's place in a category's Manual order. [entryId] is the raw row id of its [contentType]. */
data class ManualOrderPosition(
    val contentType: ContentType,
    val entryId: Long,
    val categoryId: Long,
    val position: Long,
)

/** A category's Manual order, and the sort it started from, for both content types in one store. */
interface LibraryManualOrderRepository {

    fun subscribeAll(): Flow<List<ManualOrderPosition>>

    suspend fun getCategory(categoryId: Long): List<ManualOrderPosition>

    suspend fun getForEntries(contentType: ContentType, entryIds: List<Long>): List<ManualOrderPosition>

    /** Writes [positions] over whatever those entries held in those categories, as a restore does. */
    suspend fun upsert(positions: List<ManualOrderPosition>)

    /** Replaces [categoryId]'s whole order with [positions], in one transaction. */
    suspend fun replaceCategory(categoryId: Long, positions: List<ManualOrderPosition>)

    /** Each Manual category's starting sort, as sort flags, by category id. */
    fun subscribeBases(): Flow<Map<Long, Long>>

    suspend fun setBase(categoryId: Long, sortFlags: Long)
}

/** One series' rows in a Manual order: its own, and each member's when it is a merged series. */
typealias ManualOrderTile = Set<Pair<ContentType, Long>>

/**
 * [categoryId]'s positions after [visible] tiles were dropped into a new order, when they may be only part of
 * the category (a content-type chip hides the other type). Each place a visible tile held is refilled in the
 * new order and every hidden tile keeps its place; a visible tile with no place yet follows the rest.
 */
fun reorderedPositions(
    categoryId: Long,
    stored: List<ManualOrderPosition>,
    visible: List<ManualOrderTile>,
): List<ManualOrderPosition> {
    val tileOf = HashMap<Pair<ContentType, Long>, Int>()
    visible.forEachIndexed { index, tile -> tile.forEach { tileOf[it] = index } }
    // In stored order: a hidden unit keeps its rows, a shown one is a place the new order refills.
    val slots = mutableListOf<Slot>()
    val placed = HashSet<Int>()
    stored.groupBy { it.position }.toSortedMap().values.forEach { rows ->
        val (shown, hidden) = rows.partition { (it.contentType to it.entryId) in tileOf }
        if (shown.any { placed.add(tileOf.getValue(it.contentType to it.entryId)) }) slots += Slot.Shown
        if (hidden.isNotEmpty()) slots += Slot.Hidden(hidden)
    }
    val newOrder = visible.iterator()
    val filled = slots.map { slot -> if (slot is Slot.Hidden) slot.rows else newOrder.next().rowsIn(categoryId) } +
        newOrder.asSequence().map { it.rowsIn(categoryId) }
    return filled.flatMapIndexed { position, rows -> rows.map { it.copy(position = position.toLong()) } }
}

private sealed interface Slot {
    data object Shown : Slot

    data class Hidden(val rows: List<ManualOrderPosition>) : Slot
}

private fun ManualOrderTile.rowsIn(categoryId: Long) =
    map { (type, id) -> ManualOrderPosition(type, id, categoryId, position = 0) }
