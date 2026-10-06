package reikai.data.track

/**
 * A 1..10 score whose list index is the score itself, index 0 being "unset". NovelList and RanobeDB
 * score this way; anything at or below zero is unscored, since a search result carries -1 until set.
 */
object TenPointScore {
    val list: List<String> = listOf("-") + (1..10).map { it.toString() }

    fun display(score: Double): String = if (score <= 0.0) list[0] else score.toInt().toString()
}
