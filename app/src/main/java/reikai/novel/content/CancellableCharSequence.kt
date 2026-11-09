package reikai.novel.content

import kotlin.coroutines.cancellation.CancellationException

/**
 * The regex engine reads its input through [get], so checking [isActive] there lets a cancelled caller
 * escape a catastrophic pattern, which no coroutine cancellation interrupts on its own. A user's regex
 * reaches both the chapter search and the find-and-replace rules, so both read through this.
 */
internal class CancellableCharSequence(
    private val delegate: CharSequence,
    private val isActive: () -> Boolean,
) : CharSequence {
    private var reads = 0

    override val length: Int get() = delegate.length

    override fun get(index: Int): Char {
        if ((++reads and 0xFFF) == 0 && !isActive()) throw CancellationException("Regex scan cancelled")
        return delegate[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        CancellableCharSequence(delegate.subSequence(startIndex, endIndex), isActive)

    override fun toString(): String = delegate.toString()
}
