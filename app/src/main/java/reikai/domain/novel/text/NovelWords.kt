package reikai.domain.novel.text

/**
 * Counts words the way a reader would. Chinese and Japanese put no space between words, so each Han,
 * Hiragana or Katakana character counts as one; any other run of text counts once, when it holds a
 * letter or digit, so a dash or a stray quote is no word.
 */
object NovelWords {

    fun count(text: String, from: Int = 0, to: Int = text.length): Int {
        var words = 0
        var inWord = false
        var i = from
        while (i < to) {
            val cp = text.codePointAt(i)
            when {
                isUnspacedScript(cp) -> {
                    words++
                    inWord = false
                }
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> inWord = false
                !inWord && Character.isLetterOrDigit(cp) -> {
                    words++
                    inWord = true
                }
            }
            i += Character.charCount(cp)
        }
        return words
    }

    private fun isUnspacedScript(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
        else -> false
    }
}
