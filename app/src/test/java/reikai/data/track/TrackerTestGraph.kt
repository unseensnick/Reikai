package reikai.data.track

import android.app.Application
import eu.kanade.domain.track.service.TrackPreferences
import io.mockk.every
import io.mockk.mockk
import mihon.app.di.AppGraph
import mihon.app.di.injekt.MetroInjektRegistrar
import mihon.core.metro.GraphProvider
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope

/**
 * Some trackers read their preferences through the app graph as they are built, so a JVM test that
 * builds one installs a graph standing in for the app's, the way the app installs its own: through
 * the Injekt registrar. Returns the scope it replaced, which the caller restores.
 */
fun installTrackerTestGraph(): InjektScope {
    val graph = mockk<AppGraph>(relaxed = true) {
        every { trackPreferences } returns TrackPreferences(InMemoryPreferenceStore())
    }
    val application = mockk<Application>(relaxed = true, moreInterfaces = arrayOf(GraphProvider::class))
    @Suppress("UNCHECKED_CAST")
    every { (application as GraphProvider<AppGraph>).graph } returns graph
    every { application.applicationContext } returns application
    val replaced = Injekt
    Injekt = InjektScope(MetroInjektRegistrar(application, application as GraphProvider<AppGraph>))
    return replaced
}
