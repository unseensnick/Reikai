package reikai.data.novel.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface SleepTimer {

    data object Off : SleepTimer

    /**
     * Pauses once the clock reaches [endsAt], in elapsed-realtime millis so a clock change cannot move it.
     * [minutes] is the option it was set from, which the time left cannot recover once some has passed.
     */
    data class At(val endsAt: Long, val minutes: Int) : SleepTimer

    /** Stops where the chapter being read ends. */
    data object EndOfChapter : SleepTimer
}

/**
 * The read-aloud sleep timer's decisions; [NovelTtsService] runs the countdown and pauses when told. The
 * countdown keeps running while paused, so an end that passes then only clears the timer.
 */
class TtsSleepTimer(private val clock: () -> Long) {

    val timer: StateFlow<SleepTimer>
        field = MutableStateFlow<SleepTimer>(SleepTimer.Off)

    fun setMinutes(minutes: Int) {
        timer.value = SleepTimer.At(clock() + minutes * MINUTE_MS, minutes)
    }

    /** Only for an owner that calls [takeEndOfChapter] at chapter ends; nothing clears it for one that does not. */
    fun setEndOfChapter() {
        timer.value = SleepTimer.EndOfChapter
    }

    fun clear() {
        timer.value = SleepTimer.Off
    }

    /** Whole minutes left, a started minute counting as one. */
    fun minutesLeft(at: SleepTimer.At): Int {
        val left = (at.endsAt - clock()).coerceAtLeast(0)
        return ((left + MINUTE_MS - 1) / MINUTE_MS).toInt()
    }

    /** How long until [minutesLeft] changes or the countdown ends. */
    fun untilNextTick(at: SleepTimer.At): Long {
        val left = at.endsAt - clock()
        if (left <= 0) return 0
        return (left % MINUTE_MS).takeIf { it > 0 } ?: MINUTE_MS
    }

    /** True when a countdown has reached its end, which clears it; the caller pauses. */
    fun expire(): Boolean {
        val at = timer.value as? SleepTimer.At ?: return false
        if (clock() < at.endsAt) return false
        clear()
        return true
    }

    /** True once, at the chapter end the timer was set to stop at. */
    fun takeEndOfChapter(): Boolean {
        if (timer.value != SleepTimer.EndOfChapter) return false
        clear()
        return true
    }

    /** Whether the last state published was stopped, which a session starts as. */
    private var wasStopped = true

    /**
     * Stopping clears the timer. Starting again restarts a countdown from its full length: one set while
     * nothing played would otherwise have run down already, and fired the moment reading began.
     */
    fun onPublished(stopped: Boolean) {
        if (stopped) {
            clear()
        } else if (wasStopped) {
            (timer.value as? SleepTimer.At)?.let { setMinutes(it.minutes) }
        }
        wasStopped = stopped
    }

    companion object {
        /** The countdowns the sleep timer offers, in minutes. */
        val MINUTES = listOf(15, 30, 45, 60)

        private const val MINUTE_MS = 60_000L
    }
}
