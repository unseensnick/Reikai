package reikai.domain.source

import java.net.URI

/** The host a site address is on, lowercased and without `www.`, so two spellings of one site match. */
fun siteHost(url: String?): String? =
    url?.let { runCatching { URI(it.trim()).host }.getOrNull() }?.lowercase()?.removePrefix("www.")
