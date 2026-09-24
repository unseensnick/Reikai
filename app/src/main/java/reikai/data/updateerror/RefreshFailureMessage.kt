package reikai.data.updateerror

import android.content.Context
import eu.kanade.presentation.util.formattedMessage
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.source.model.SourceNotInstalledException
import tachiyomi.i18n.MR

/** A library update's failure for one entry as Mihon's `LibraryUpdateJob` words it; the manga and novel
 *  jobs both ask here. */
context(context: Context)
fun Throwable.updateFailureMessage(): String? = when (this) {
    is NoChaptersException -> context.stringResource(MR.strings.no_chapters_error)
    // The failure list already names the source, so the message does not repeat it.
    is SourceNotInstalledException -> context.stringResource(MR.strings.loader_not_implemented_error)
    else -> message
}

/**
 * A details refresh's failure as Mihon's `MangaViewModel` shows it, logging anything but an empty chapter
 * list as it does; the manga and novel details screens both ask here.
 */
context(context: Context)
fun Throwable.refreshFailureMessage(): String = if (this is NoChaptersException) {
    context.stringResource(MR.strings.no_chapters_error)
} else {
    this.logcat(LogPriority.ERROR, this)
    formattedMessage
}
