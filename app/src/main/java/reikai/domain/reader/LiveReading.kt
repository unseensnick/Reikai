package reikai.domain.reader

import reikai.domain.readinglog.PaceUnit

/** One moment of reading: the chapter, when, and how much of it lies before the position on screen. */
data class ReadingMark(val chapterId: Long, val atMs: Long, val unitsBefore: Double)

/**
 * The reader's pace measured this session, which the time left falls back on until finished chapters
 * teach one. It runs from the latest point reading was continuous: a jump ahead, a step back or a new
 * chapter measures again from there, keeping the pace already measured until the new stretch gives one.
 * [progress] is the position it last measured from, which the time left counts from too, so one step
 * serves both.
 */
data class LiveReading(
    val anchor: ReadingMark? = null,
    val last: ReadingMark? = null,
    val msPerUnit: Double? = null,
    val progress: ChapterProgress? = null,
) {

    /** The state after the reader shows [progress] of a chapter [units] long, [nowMs] on a monotonic clock. */
    fun next(chapterId: Long, units: Long, progress: ChapterProgress?, nowMs: Long, unit: PaceUnit): LiveReading {
        val before = progress?.unitsBefore(units) ?: return LiveReading(msPerUnit = msPerUnit)
        val mark = ReadingMark(chapterId, nowMs, before)
        // A new chapter keeps the pace, since the reader has not changed: its history may not be written
        // yet when the next chapter asks for a learned one.
        val previous = last?.takeIf { it.chapterId == chapterId }
            ?: return LiveReading(anchor = mark, last = mark, msPerUnit = msPerUnit, progress = progress)
        val from = anchor ?: previous
        val step = mark.unitsBefore - previous.unitsBefore
        val sinceAnchor = mark.unitsBefore - from.unitsBefore
        val elapsed = mark.atMs - from.atMs
        // A scroll reports its progress in small steps a moment apart, so speed is judged over a big step
        // alone (a slider jump) and over the whole stretch (pages flipped through), never per small step.
        val leapt = step >= unit.minLiveUnits && step * unit.floorMs > mark.atMs - previous.atMs
        val skimmed = sinceAnchor * unit.floorMs > elapsed
        if (step < 0 || leapt || skimmed) {
            return LiveReading(anchor = mark, last = mark, msPerUnit = msPerUnit, progress = progress)
        }
        val measured = (elapsed / sinceAnchor)
            .takeIf { sinceAnchor >= unit.minLiveUnits && elapsed >= MIN_MS && it in unit.floorMs..unit.capMs }
        return LiveReading(anchor = from, last = mark, msPerUnit = measured ?: msPerUnit, progress = progress)
    }
}

/** Enough reading that one long or short page, or one screenful, does not swing the pace. */
private val PaceUnit.minLiveUnits: Double
    get() = when (this) {
        PaceUnit.PAGE -> 3.0
        PaceUnit.WORD -> 150.0
    }

private const val MIN_MS = 20_000L
