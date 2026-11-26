package reikai.presentation.library

import android.content.Context
import dev.icerock.moko.resources.StringResource
import reikai.domain.library.tagKey
import reikai.presentation.components.EntryStatus
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * Language-neutral ids for the dynamic groups the app names itself (publishing and tracking status,
 * Unknown, Not tracked, Ungrouped), so a group's stored collapse state survives a language change. They are
 * persisted in `collapsed_dynamic_categories` and backups: never rename one.
 */
object DynamicGroupKeys {

    const val UNKNOWN = "rk:unknown"
    const val UNKNOWN_SOURCE = "rk:unknownsource"
    const val NOT_TRACKED = "rk:nottracked"
    const val UNGROUPED = "rk:ungrouped"

    /** An unknown status is the one Unknown group every mode shares, as its label always was. */
    fun status(status: EntryStatus): String =
        if (status == EntryStatus.UNKNOWN) UNKNOWN else "rk:status:${status.name.lowercase()}"

    /**
     * A tracking status group's name: keyed by [TRACK_STATUSES], which every tracker's statuses must be in
     * (pinned by DynamicGroupKeysTest); a status missing from it still groups, keyed by its [label].
     */
    fun trackStatusName(status: StringResource, label: String): String =
        TRACK_STATUSES[status]?.let { labelledGroupName("rk:track:$it", label) } ?: label

    internal val TRACK_STATUSES: Map<StringResource, String> = mapOf(
        MR.strings.reading to "reading",
        MR.strings.completed to "completed",
        MR.strings.on_hold to "onhold",
        MR.strings.dropped to "dropped",
        MR.strings.plan_to_read to "plantoread",
        MR.strings.repeating to "repeating",
        MR.strings.paused to "paused",
        MR.strings.unread to "unread",
        MR.strings.considering to "considering",
        MR.strings.reading_list to "readinglist",
        MR.strings.wish_list to "wishlist",
        MR.strings.complete_list to "completelist",
        MR.strings.unfinished_list to "unfinishedlist",
        MR.strings.on_hold_list to "onholdlist",
        MR.strings.md_follows_unfollowed to "unfollowed",
    )

    /**
     * [stored] with the neutral key added for each key saved under a label in the app's current language,
     * the format before these ids. The label key stays, since a tag of the same name may own it. Shared by
     * the upgrade (CollapsedGroupKeysMigration) and a restore of an older backup.
     */
    fun withNeutralKeys(stored: Set<String>, context: Context): Set<String> {
        val byLabel = labelKeys(context)
        return stored + stored.flatMap { byLabel[normalizeDynamicKey(it)].orEmpty() }
    }

    private fun labelKeys(context: Context): Map<String, Set<String>> {
        fun keyOf(id: String) = normalizeDynamicKey(labelledGroupName(id, ""))
        val unknown = context.stringResource(MR.strings.unknown)
        val pairs = EntryStatus.entries.map { context.stringResource(it.label) to keyOf(status(it)) } +
            TRACK_STATUSES.map { (res, id) -> context.stringResource(res) to keyOf("rk:track:$id") } +
            listOf(
                unknown to keyOf(UNKNOWN),
                context.stringResource(MR.strings.not_tracked) to keyOf(NOT_TRACKED),
                context.stringResource(MR.strings.group_ungrouped) to keyOf(UNGROUPED),
            )
        val sourceUnknown = tagKey("$unknown${LibraryDynamicGrouping.SOURCE_SPLITTER}0") to setOf(keyOf(UNKNOWN_SOURCE))
        return pairs.groupBy({ tagKey(it.first) }, { it.second }).mapValues { it.value.toSet() } + sourceUnknown
    }
}
