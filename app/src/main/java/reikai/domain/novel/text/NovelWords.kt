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

    /**
     * Whatever runs between spaces, a no-break space included, as Tsundoku's word-count dialog counts. The
     * dialog's figure is theirs (an owner ruling), so a Chinese or Japanese chapter counts a few words here
     * where [count], which the split and read-aloud need, counts every character.
     */
    fun countSpaced(text: String): Int {
        var words = 0
        var inWord = false
        for (char in text) {
            // Kotlin's isWhitespace also answers true for a no-break space, which Java's does not.
            val isSpace = char.isWhitespace()
            if (!isSpace && !inWord) words++
            inWord = !isSpace
        }
        return words
    }

    private fun isUnspacedScript(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
        else -> false
    }
}
