package reikai.util

import android.content.Context
import android.os.Debug
import java.io.File

/**
 * Dev builds: when the app dies of running out of memory, writes the heap to oom.hprof in its external
 * files folder (Android/data/<package>/files, which adb can pull), so what filled it can be read after the
 * fact. Wraps the crash handler installed before it, which still gets every crash.
 */
fun Context.dumpHeapOnFatalOom() {
    val app = applicationContext
    val next = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler(
        heapDumpOnOom(next) {
            // Resolved here, not at install: the folder lookup is file I/O, kept off startup.
            app.getExternalFilesDir(null)?.let { Debug.dumpHprofData(File(it, "oom.hprof").path) }
        },
    )
}

internal fun heapDumpOnOom(
    next: Thread.UncaughtExceptionHandler?,
    dump: () -> Unit,
): Thread.UncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, e ->
    if (e.firstCause { it as? OutOfMemoryError } != null) runCatching(dump)
    next?.uncaughtException(thread, e)
}
