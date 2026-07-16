package reikai.novel.host

import org.jsoup.parser.Parser

/**
 * Cleanup for text coming out of lnreader plugins before it is stored, shown, or used to name a
 * download folder. Plugins return HTML-escaped strings and, occasionally, control characters that
 * corrupt a folder name or the saved chapter file. Ported from tsundoku's JsSource.
 */
object NovelTextSanitizer {

    /**
     * Drop characters that are illegal in XML and unsafe in a file name: the C0 control codes except
     * the tab / line-feed / carriage-return that are legal, plus the two non-characters. All real text
     * is left untouched.
     */
    fun stripInvalidChars(text: String): String = text.filter { c ->
        val code = c.code
        val isIllegalControl = code < 0x20 && code != 0x09 && code != 0x0A && code != 0x0D
        !isIllegalControl && code != 0xFFFE && code != 0xFFFF
    }

    /**
     * Decode HTML entities (`&amp;` becomes `&`) then strip invalid characters. For plain-text fields
     * (title, author, chapter name), not HTML bodies, whose markup must survive intact.
     */
    fun decodeEntities(text: String): String = stripInvalidChars(Parser.unescapeEntities(text, true))
}
