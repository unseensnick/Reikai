package reikai.data.track

import eu.kanade.tachiyomi.BuildConfig

/** The identifying user agent the trackers are sent. Shikimori IP-bans a missing or browser one. */
const val REIKAI_TRACKER_USER_AGENT = "Reikai v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})"
