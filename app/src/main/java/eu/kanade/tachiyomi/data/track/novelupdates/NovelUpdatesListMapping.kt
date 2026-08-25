package eu.kanade.tachiyomi.data.track.novelupdates

import kotlinx.serialization.json.Json

/**
 * Which NovelUpdates list each status lives on, in both directions.
 *
 * The site lets a user rename and add lists, so a status can sit on a list of their choosing. The
 * reference fork honours that map when writing and ignores it when reading, so a custom-mapped
 * status comes back as something else; here one map answers both ways.
 */
class NovelUpdatesListMapping(private val statusToList: Map<Long, Long>) {

    private val listToStatus: Map<Long, Long> =
        statusToList.entries
            .sortedBy { it.key }
            .associate { (status, list) -> list to status }

    fun listIdFor(status: Long): Long = statusToList[status] ?: statusToList.getValue(NovelUpdates.READING)

    /** Null for a list this mapping does not know, so the caller keeps the status it already had. */
    fun statusFor(listId: Long): Long? = listToStatus[listId]

    /** The map as stored, for the settings picker to edit. */
    fun asStatusToList(): Map<Long, Long> = statusToList

    companion object {
        /** NovelUpdates' five stock lists, in the order the site creates them. */
        val Default = NovelUpdatesListMapping(
            mapOf(
                NovelUpdates.READING to 0L,
                NovelUpdates.COMPLETED to 1L,
                NovelUpdates.PLAN_TO_READ to 2L,
                NovelUpdates.ON_HOLD to 3L,
                NovelUpdates.DROPPED to 4L,
            ),
        )

        /**
         * Reads the stored `status -> listId` map, falling back to [Default] when the preference is
         * empty or unreadable. A partial map keeps the defaults for the statuses it omits, so a user
         * who remapped one list does not lose the other four.
         */
        fun from(stored: String, json: Json): NovelUpdatesListMapping {
            val parsed = runCatching { json.decodeFromString<Map<String, Long>>(stored) }.getOrNull()
                ?.mapNotNull { (status, list) -> status.toLongOrNull()?.let { it to list } }
                ?.toMap()
                ?.takeIf { it.isNotEmpty() }
                ?: return Default
            return NovelUpdatesListMapping(Default.statusToList + parsed)
        }
    }
}
