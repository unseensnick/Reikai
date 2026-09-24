package eu.kanade.presentation.util

import android.content.Context
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.util.system.isOnline
import reikai.util.firstCause
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.data.source.NoResultsException
import tachiyomi.domain.source.model.SourceNotInstalledException
import tachiyomi.i18n.MR
import java.net.UnknownHostException

context(context: Context)
val Throwable.formattedMessage: String
    get() {
        // RK --> an offline source error arrives wrapped, and read off the wrapper it shows the raw text
        val cause = formattableCause()
        if (cause !== this) return cause.formattedMessage
        // RK <--
        when (this) {
            is HttpException -> return context.stringResource(MR.strings.exception_http, code)
            is UnknownHostException -> {
                return if (!context.isOnline()) {
                    context.stringResource(MR.strings.exception_offline)
                } else {
                    context.stringResource(MR.strings.exception_unknown_host, message ?: "")
                }
            }

            is NoResultsException -> return context.stringResource(MR.strings.no_results_found)
            is SourceNotInstalledException -> return context.stringResource(MR.strings.loader_not_implemented_error)
        }
        return when (val className = this::class.simpleName) {
            "Exception", "IOException" -> message ?: className
            else -> "$className: $message"
        }
    }

// RK --> the first throwable in the cause chain the formatter has a message for, or this one
internal fun Throwable.formattableCause(): Throwable = firstCause {
    it.takeIf { cause ->
        cause is HttpException ||
            cause is UnknownHostException ||
            cause is NoResultsException ||
            cause is SourceNotInstalledException
    }
} ?: this
// RK <--
