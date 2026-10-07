package reikai.util

import android.content.Intent

/**
 * The launcher icon's launch, the one MainActivity drops when it lands off its task's root, since the
 * app's task already holds it. Any other sender (Files opening a backup, a repo link) may start it in
 * the sender's own task, where it is never root, so dropping those swallowed the launch.
 */
fun Intent.isLauncherIntent(): Boolean = action == Intent.ACTION_MAIN && hasCategory(Intent.CATEGORY_LAUNCHER)
