package reikai.novel.content

import kotlin.coroutines.cancellation.CancellationException

/**
 * Stops a JVM regex scan once [isActive] turns false, since that engine reads its input through [get].
 * Android's does not (it copies the input and matches natively), so [UserRegexRunner] owns cancelling
 * a user regex and uses this only to end its abandoned thread where the engine allows.
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
