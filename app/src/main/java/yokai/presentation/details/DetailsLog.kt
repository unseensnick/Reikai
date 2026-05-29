package yokai.presentation.details

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.BuildConfig

/**
 * Temporary phase-testing aid for the details Compose port. Logs under the Kermit tag
 * "DetailsPort" so on-device logcat can be filtered to just this surface (tag:DetailsPort),
 * away from the framework render/window noise. Debug builds only, so nothing reaches
 * Crashlytics. Remove once the port stabilizes.
 */
private val detailsLogger = Logger.withTag("DetailsPort")

internal fun detailsLog(message: () -> String) {
    if (BuildConfig.DEBUG) detailsLogger.d(message())
}
