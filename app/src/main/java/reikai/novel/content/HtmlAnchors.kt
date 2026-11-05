package reikai.novel.content

import org.jsoup.nodes.Element
import java.net.URLDecoder

/**
 * [value] percent-decoded with a `+` kept as itself, as a path or fragment in a link reads it (only a form
 * body spells a space `+`); null when it is not well-formed.
 */
fun decodePercentKeepingPlus(value: String): String? =
    runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrNull()

/**
 * The element under [root] a link's fragment [name] points at: its id, then an old-style `name`, then the
 * `xml:id` an XHTML book may carry instead. [name] must not be empty, since jsoup throws on one.
 */
fun elementNamed(root: Element, name: String): Element? = root.getElementById(name)
    ?: root.getElementsByAttributeValue("name", name).firstOrNull()
    ?: root.getElementsByAttributeValue("xml:id", name).firstOrNull()
