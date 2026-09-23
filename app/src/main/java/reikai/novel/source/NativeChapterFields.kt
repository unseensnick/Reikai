package reikai.novel.source

import java.time.Instant

/** A native upload date as an ISO instant, the one form the novel date parser keeps to the millisecond; 0 is unknown. */
internal fun releaseTimeOf(uploadedAtMillis: Long): String? =
    uploadedAtMillis.takeIf { it > 0L }?.let { Instant.ofEpochMilli(it).toString() }

/** A native chapter number, or null for a negative one, which a source that numbers nothing stores. */
internal fun chapterNumberOf(number: Float): Double? = number.takeIf { it >= 0f }?.toDouble()
