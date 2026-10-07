package exh.util

import org.jsoup.nodes.Element

/** This image's absolute address, read from the attribute a lazy loader or Cloudflare moved it to. */
internal fun Element.lazyImageUrl(): String? = when {
    hasAttr("data-src") -> absUrl("data-src")
    hasAttr("data-cfsrc") -> absUrl("data-cfsrc")
    else -> absUrl("src")
}.ifBlank { null }
