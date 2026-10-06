package reikai.domain.recommendation

import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import reikai.util.runCatchingCancellable
import kotlin.time.Duration.Companion.seconds

/**
 * Every recommendation fetch, tracker or source, runs under this one rule: a call still running after
 * the cap is dropped silently, a failure is logged as [failure] and dropped, and a cancellation (the
 * screen closed) propagates. The cap is long enough for AniList's GraphQL on a slow link and short
 * enough that one hung call cannot hold the carousel's load-complete signal for the socket timeout.
 */
internal suspend fun <T : Any> cappedRecommendationCall(failure: () -> String, block: suspend () -> T): T? =
    runCatchingCancellable { withTimeoutOrNull(15.seconds) { block() } }
        .onFailure { logcat("Recommendations", LogPriority.WARN) { "${failure()}\n${it.asLog()}" } }
        .getOrNull()
