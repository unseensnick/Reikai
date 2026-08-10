package reikai.util

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkQuery
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Whether any work tagged [tag] is running, as a flow rather than the one-shot `isRunning` beside it,
 * so a screen can show a refreshing state that ends when the job does. Written once because both
 * library update jobs need it and each only supplies its own tag.
 *
 * A job's tag covers its scheduled and its manual request alike, which is what `startNow` already
 * treats as "already running", so this reports a background update too, not only a pulled one.
 */
fun Context.workRunningFlow(tag: String): Flow<Boolean> = workManager
    .getWorkInfosFlow(WorkQuery.fromTags(tag))
    .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
    .distinctUntilChanged()
