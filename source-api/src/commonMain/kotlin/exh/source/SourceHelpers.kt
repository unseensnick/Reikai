package exh.source

import eu.kanade.tachiyomi.source.Source

/**
 * Unwraps an [EnhancedHttpSource] to its enhanced delegate (e.g. to reach the [UrlImportableSource]
 * a wrapped adult source implements); returns the source itself when it is not wrapped.
 */
inline fun <reified T : Source> Source.getMainSource(): T? = if (this is EnhancedHttpSource) {
    enhancedSource as? T
} else {
    this as? T
}
