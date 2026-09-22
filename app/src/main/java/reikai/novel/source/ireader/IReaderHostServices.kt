package reikai.novel.source.ireader

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import ireader.core.source.Dependencies
import tachiyomi.core.common.preference.PreferenceStore

/**
 * What an IReader extension's source is constructed with: one set of HTTP clients shared by every
 * extension, and settings kept per package.
 */
@Inject
@SingleIn(AppScope::class)
class IReaderHostServices(
    private val context: Context,
    private val networkHelper: NetworkHelper,
    private val preferenceStore: PreferenceStore,
) {

    // Built on first use rather than with the graph: nothing needs it until an IReader extension loads.
    private val httpClients by lazy {
        IReaderHttpClients(context, networkHelper.client, networkHelper::defaultUserAgentProvider)
    }

    fun dependenciesFor(packageName: String): Dependencies =
        Dependencies(httpClients, IReaderPreferenceStore(preferenceStore, packageName))
}
