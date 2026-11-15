package reikai.util

import android.content.Intent

/**
 * The launcher icon's launch, the one MainActivity drops when it lands off its task's root, since the
 * app's task already holds it. Any other sender (Files opening a backup, a repo link) may start it in
 * the sender's own task, where it is never root, so it is handed over with [toOwnTask] instead.
 */
fun Intent.isLauncherIntent(): Boolean = action == Intent.ACTION_MAIN && hasCategory(Intent.CATEGORY_LAUNCHER)

/**
 * This launch, moved into the app's own task. It reaches the screen already there through onNewIntent, or
 * starts the task, and carries on the sender's read grant, which ends with the copy in the sender's task.
 */
fun Intent.toOwnTask(): Intent = Intent(this).setFlags(ownTaskFlags(flags)).putExtra(HANDED_OVER, true)

/** A launch [toOwnTask] made, which runs wherever it lands so it can never be handed over again. */
fun Intent.isHandedOver(): Boolean = getBooleanExtra(HANDED_OVER, false)

// Only the grants: the sender's own launch flags (a new document, a task of its own) are not ours to keep.
internal fun ownTaskFlags(incoming: Int): Int = (incoming and URI_GRANTS) or
    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

private val URI_GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

private const val HANDED_OVER = "reikai.handed_over"
