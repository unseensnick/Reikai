package reikai.presentation.settings

import reikai.novel.host.LnPluginHost
import reikai.novel.source.isSensitivePluginKey
import reikai.novel.source.lnPluginIdOf
import reikai.novel.source.pluginStorageScope
import tachiyomi.core.common.preference.PreferenceStore

/**
 * The LN plugins whose copy of a site's storage, with its sign-in, sits among the app-store [keys].
 * Clearing the WebView's data or cookies forgets each, as LNReader's Clear cookies does, or the plugin
 * would keep signing in with what the user just cleared.
 */
fun pluginsKeepingWebStorage(keys: Collection<String>): Set<String> =
    keys.filter(::isSensitivePluginKey).mapNotNullTo(HashSet()) { key -> pluginStorageScope(key)?.let(::lnPluginIdOf) }

/** Forgets every plugin's copy of its site's storage, held in [store]. */
fun LnPluginHost.forgetAllWebStorage(store: PreferenceStore) =
    pluginsKeepingWebStorage(store.getAll().keys).forEach(::forgetWebStorage)
