package exh.source

import eu.kanade.tachiyomi.source.ConfigurableSource
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

/**
 * The source whose settings a settings screen shows, null when there are none. A delegated source
 * arrives wrapped and the wrapper is never configurable: its delegate carries the settings when it has
 * any (and delegation is on), else the installed extension underneath does.
 */
fun Source.configurableSource(): ConfigurableSource? {
    val unwrapped = if (this is EnhancedHttpSource) {
        if (enhancedSource is ConfigurableSource) source() else originalSource
    } else {
        this
    }
    return unwrapped as? ConfigurableSource
}
