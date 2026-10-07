package reikai.data.notification

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

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
