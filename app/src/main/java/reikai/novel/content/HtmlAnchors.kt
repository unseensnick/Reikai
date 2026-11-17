package reikai.novel.content

import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * [value] percent-decoded with a `+` kept as itself, as a path or fragment in a link reads it (only a form
 * body spells a space `+`); null when it is not well-formed, an escape that is not UTF-8 included. That is
 * where `decodeURIComponent` throws, so reader.js, which decodes with it, names nothing by it either.
 */
fun decodePercentKeepingPlus(value: String): String? = runCatching {
    val bytes = ByteArrayOutputStream()
    var i = 0
    while (i < value.length) {
        if (value[i] == '%') {
            val hex = value.substring(i + 1, i + 3)
            // ASCII only, as decodeURIComponent reads it: toInt also takes a sign and other scripts' digits.
            require(hex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) { "Not an escape: %$hex" }
            bytes.write(hex.toInt(16))
            i += 3
        } else {
            val next = value.indexOf('%', i).takeIf { it >= 0 } ?: value.length
            bytes.write(value.substring(i, next).toByteArray(Charsets.UTF_8))
            i = next
        }
    }
    // The decoder reports malformed input, where the platform's URLDecoder writes a replacement character.
    Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
}.getOrNull()

/**
 * The element under [root] a link's fragment [name] points at: its id, then an old-style `name`, then the
 * `xml:id` an XHTML book may carry instead. [name] must not be empty, since jsoup throws on one.
 */
fun elementNamed(root: Element, name: String): Element? = root.getElementById(name)
    ?: root.getElementsByAttributeValue("name", name).firstOrNull()
    ?: root.getElementsByAttributeValue("xml:id", name).firstOrNull()

/**
 * The element a link's [fragment] names under [root]: as written first, then percent-decoded, the order a
 * browser tries and reader.js mirrors, each by [elementNamed]. An empty name names nothing.
 */
fun anchorTarget(root: Element, fragment: String): Element? {
    val decoded = decodePercentKeepingPlus(fragment)
    return listOfNotNull(fragment, decoded).distinct().filter { it.isNotEmpty() }.firstNotNullOfOrNull { name ->
        elementNamed(root, name)
    }
}
