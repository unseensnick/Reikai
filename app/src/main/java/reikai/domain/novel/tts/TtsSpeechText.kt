package reikai.domain.novel.tts

/**
 * A paragraph as the voice is given it: brackets and quote marks, which some voices read out by name,
 * replaced while the words inside them stay. One character for one, so an offset into the paragraph as
 * shown is the same offset here, which the sentence mark depends on.
 */
object TtsSpeechText {

    private const val BRACKETS = "()[]{}<>【】〔〕〖〗「」『』《》〈〉（）［］｛｝«»‹›⟨⟩⟦⟧"
    private const val QUOTES = "\"“”„‟'‘’‚‛＂＇〝〞〟"

    fun of(text: String): String {
        val out = CharArray(text.length)
        // The last character the voice will say, and whether a pause has been given since it.
        var lastSaid: Char? = null
        var paused = false
        text.forEachIndexed { i, c ->
            out[i] = when {
                isDropped(text, i) && c in QUOTES -> ' '
                // A comma between two words, once; beside punctuation it would be a second pause.
                c in BRACKETS -> if (!paused && lastSaid.isWord() && nextSaid(text, i).isWord()) {
                    paused = true
                    ','
                } else {
                    ' '
                }
                else -> {
                    if (!c.isWhitespace()) {
                        lastSaid = c
                        paused = false
                    }
                    c
                }
            }
        }
        return String(out)
    }

    /** A bracket, or a quote mark not between two letters: an apostrophe inside a word is said. */
    private fun isDropped(text: String, i: Int): Boolean = when (text[i]) {
        in BRACKETS -> true
        in QUOTES -> !(text.getOrNull(i - 1)?.isLetter() == true && text.getOrNull(i + 1)?.isLetter() == true)
        else -> false
    }

    private fun nextSaid(text: String, from: Int): Char? =
        (from + 1 until text.length).firstOrNull { !text[it].isWhitespace() && !isDropped(text, it) }?.let(text::get)

    private fun Char?.isWord() = this?.isLetterOrDigit() == true
}
