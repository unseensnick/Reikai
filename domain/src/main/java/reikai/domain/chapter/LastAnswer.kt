package reikai.domain.chapter

/**
 * The last answer one screen asked for, reused while both inputs are equal. A download tick or a selection
 * tap re-renders a chapter list over the same rows, so a walk over every chapter is not run again for it.
 */
open class LastAnswer<A, B, R> {
    private var last: Triple<A, B, R>? = null

    @Synchronized
    fun get(first: A, second: B, compute: () -> R): R {
        last?.let { (lastFirst, lastSecond, answer) -> if (lastFirst == first && lastSecond == second) return answer }
        return compute().also { last = Triple(first, second, it) }
    }
}
