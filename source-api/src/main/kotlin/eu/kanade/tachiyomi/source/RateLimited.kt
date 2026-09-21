package eu.kanade.tachiyomi.source

/**
 * A source's own request pacing. Part of the contract novel APKs compile against
 * (tsundoku-otaku/extensions-lib 1.6), so every member keeps that library's exact name and
 * signature; `SourceApiContractTest` pins them.
 */
interface RateLimited {

    /** The least delay between requests the host may allow. */
    val minimumDelayMillis: Long

    /** Seeds the host's default delay. */
    val recommendedDelayMillis: Long
        get() = minimumDelayMillis

    /** Requests allowed in a burst before the recommended delay applies; a default, not a cap. */
    val recommendedPermits: Int
        get() = 1
}
