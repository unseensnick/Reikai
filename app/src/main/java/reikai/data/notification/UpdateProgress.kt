package reikai.data.notification

import android.os.SystemClock
import eu.kanade.tachiyomi.util.lang.chop
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * How far an update has got, as its progress title shows it ("66%"), rounded down so it never reads
 * 100% before the last entry. Divided exactly: upstream's `current.toFloat() / total` read 7 of 10 as 69%.
 */
fun updateProgressPercent(
    current: Int,
    total: Int,
    locale: Locale = Locale.getDefault(Locale.Category.FORMAT),
): String {
    val fraction = BigDecimal.valueOf(current.toLong()).divide(BigDecimal.valueOf(total.toLong()), 2, RoundingMode.DOWN)
    return NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 0 }.format(fraction)
}

/**
 * Lets a library update's progress notice through at most once per 500 ms, faster than the system shows them
 * anyway; the last one ([isFinal]) always passes so the bar ends full. Safe across concurrent updaters.
 */
class UpdateProgressThrottle(private val clock: () -> Long = SystemClock::elapsedRealtime) {

    private val lastShown = AtomicLong(0L)

    fun tryPass(isFinal: Boolean): Boolean {
        val now = clock()
        val last = lastShown.get()
        if (!isFinal && now - last < UPDATE_PROGRESS_INTERVAL_MS) return false
        return lastShown.compareAndSet(last, now) || isFinal
    }
}

private const val UPDATE_PROGRESS_INTERVAL_MS = 500L

/** The body of an update's progress notice: every title being updated right now, one per line. */
fun updatingText(titles: List<String>): String = titles.joinToString("\n") { it.chop(TITLE_LENGTH) }

private const val TITLE_LENGTH = 40
