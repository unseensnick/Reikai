package reikai.presentation.browse

import android.content.Context
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.presentation.util.wordedCause
import eu.kanade.tachiyomi.util.system.isOnline
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * What a source that failed to list or search says. Offline, a failure the formatter has no wording for
 * is the lost connection: a source can swallow the network error and fail later on what it never
 * received (an index error, a refused LAN host). Not the reader's rule, where a stored page can fail
 * offline for reasons of its own.
 */
context(context: Context)
val Throwable.sourceFailureMessage: String
    get() = if (wordedCause() == null && !context.isOnline()) {
        context.stringResource(MR.strings.exception_offline)
    } else {
        formattedMessage
    }
