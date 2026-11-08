package reikai.domain.novel.tts

/**
 * A paragraph as the voice is given it: brackets, quote marks, tildes and decorative symbols,
 * which some voices read out by name, replaced while the words between them stay. One character for one,
 * so an offset into the paragraph as shown is the same offset here, which the sentence mark depends on.
 */
object TtsSpeechText {

    private const val BRACKETS = "()[]{}<>【】〔〕〖〗「」『』《》〈〉（）［］｛｝«»‹›⟨⟩⟦⟧"
    private const val QUOTES = "\"“”„‟'‘’‚‛＂＇〝〞〟"
    private const val TILDES = "~～〜"

    // Never a dash: voices pause on one by themselves, and a hyphen or ー is part of a word.
    private const val SYMBOLS = "♪♫♬♩♡♥❤☆★◇◆○●□■※＊*・･•"

    fun of(text: String): String {
        val out = CharArray(text.length)
        // The last character the voice will say, and whether a pause has been given since it.
        var lastSaid: Char? = null
        var paused = false
        text.forEachIndexed { i, c ->
            out[i] = when {
                // A comma between two words, once; beside punctuation it would be a second pause.
                c in BRACKETS -> if (!paused && lastSaid.isWord() && nextSaid(text, i).isWord()) {
                    paused = true
                    ','
                } else {
                    ' '
                }
                isDropped(text, i) -> ' '
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

    /**
     * Never said. A quote mark between two letters is an apostrophe and a tilde between two digits a range
     * ("5~10"), so those stay.
     */
    private fun isDropped(text: String, i: Int): Boolean = when (text[i]) {
        in BRACKETS, in SYMBOLS -> true
        in QUOTES -> !text.isBetween(i, Char::isLetter)
        in TILDES -> !text.isBetween(i, Char::isDigit)
        else -> false
    }

    private fun String.isBetween(i: Int, kind: (Char) -> Boolean) =
        getOrNull(i - 1)?.let(kind) == true && getOrNull(i + 1)?.let(kind) == true

    private fun nextSaid(text: String, from: Int): Char? =
        (from + 1 until text.length).firstOrNull { !text[it].isWhitespace() && !isDropped(text, it) }?.let(text::get)

    private fun Char?.isWord() = this?.isLetterOrDigit() == true
}
