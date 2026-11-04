package reikai.domain.readinglog

import reikai.domain.library.ContentType

/**
 * A finished chapter's reading time and length in its type's [PaceUnit], from history: [durationMs] is
 * every session spent in it, re-reads included, which is why [learnPace] takes a median. [listenedMs] is
 * the part of it read aloud, which only a novel has.
 */
data class PaceSample(val entryId: Long, val durationMs: Long, val units: Long, val listenedMs: Long = 0) {
    /** Mostly listened to, so it teaches the voice's pace rather than the reader's. */
    val isListened: Boolean get() = listenedMs * 2 > durationMs
}

/** How long one unit takes to read, or [Learning] until enough finished chapters say. */
sealed interface ReadingPace {
    data object Learning : ReadingPace

    data class Known(val msPerUnit: Double) : ReadingPace
}

/**
 * What a chapter's length is counted in, with the time per unit a chapter must fall within to count:
 * under the floor is tapping through, over the cap is a reader left open (KOReader bounds a page the same
 * way). Manga counts pages and novels words.
 */
enum class PaceUnit(val floorMs: Double, val capMs: Double) {
    PAGE(floorMs = 1_000.0, capMs = 120_000.0),

    // 1,200 and 30 words a minute.
    WORD(floorMs = 50.0, capMs = 2_000.0),
    ;

    companion object {
        fun of(contentType: ContentType): PaceUnit = when (contentType) {
            ContentType.MANGA -> PAGE
            ContentType.NOVELS -> WORD
            ContentType.ALL -> error("ContentType.ALL has no pace unit")
        }
    }
}

/**
 * The pace for a series from [samples], newest first: its own most recent chapters once it has
 * [MIN_SAMPLES] of them, else the most recent across everything read, else [ReadingPace.Learning]. A
 * series is every entry in [seriesIds], so a merged one learns from each member's chapters; null asks for
 * the overall pace.
 */
fun learnPace(samples: List<PaceSample>, seriesIds: Set<Long>?, unit: PaceUnit): ReadingPace {
    val plausible = samples.filter { it.units > 0 && it.msPerUnit in unit.floorMs..unit.capMs }
    val own = plausible.filter { seriesIds != null && it.entryId in seriesIds }.take(RECENT_SAMPLES)
    val pool = if (own.size >= MIN_SAMPLES) own else plausible.take(RECENT_SAMPLES)
    if (pool.size < MIN_SAMPLES) return ReadingPace.Learning
    val sorted = pool.map { it.msPerUnit }.sorted()
    val mid = sorted.size / 2
    return ReadingPace.Known(if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2)
}

private val PaceSample.msPerUnit: Double get() = durationMs.toDouble() / units

/** How many finished chapters a pace needs, and how many of the newest it is taken from. */
private const val MIN_SAMPLES = 3
private const val RECENT_SAMPLES = 20
