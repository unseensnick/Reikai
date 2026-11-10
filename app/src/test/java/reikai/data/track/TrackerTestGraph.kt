package reikai.data.track

import android.app.Application
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.TrackerDependencies
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.bangumi.Bangumi
import eu.kanade.tachiyomi.data.track.comick.Comick
import eu.kanade.tachiyomi.data.track.hikka.Hikka
import eu.kanade.tachiyomi.data.track.kavita.Kavita
import eu.kanade.tachiyomi.data.track.kitsu.Kitsu
import eu.kanade.tachiyomi.data.track.komga.Komga
import eu.kanade.tachiyomi.data.track.mangabaka.MangaBaka
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdates
import eu.kanade.tachiyomi.data.track.mdlist.MdList
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.data.track.novellist.NovelList
import eu.kanade.tachiyomi.data.track.novelupdates.NovelUpdates
import eu.kanade.tachiyomi.data.track.ranobedb.RanobeDb
import eu.kanade.tachiyomi.data.track.shikimori.Shikimori
import eu.kanade.tachiyomi.data.track.suwayomi.Suwayomi
import eu.kanade.tachiyomi.network.NetworkHelper
import io.mockk.every
import io.mockk.mockk
import mihon.app.di.AppBindings
import mihon.app.di.AppGraph
import mihon.app.di.injekt.MetroInjektRegistrar
import mihon.core.metro.GraphProvider
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope

/**
 * What a tracker takes from the graph, for a JVM test that builds one. [network] and [preferences] are
 * the two a test usually stubs; the rest are relaxed. The default store drops writes; a test that reads
 * a stored login back passes one that keeps them.
 */
fun trackerTestDependencies(
    network: NetworkHelper = mockk(relaxed = true),
    preferences: PreferenceStore = InMemoryPreferenceStore(),
): TrackerDependencies = TrackerDependencies(
    context = mockk(relaxed = true),
    trackPreferences = TrackPreferences(preferences),
    networkHelper = lazyOf(network),
    addTracks = lazy { mockk(relaxed = true) },
    upsertTrack = lazy { mockk(relaxed = true) },
)

/** Every tracker over [dependencies], as the graph builds the manager; their own collaborators are relaxed. */
fun trackerTestManager(dependencies: TrackerDependencies = trackerTestDependencies()): TrackerManager {
    val json = AppBindings.providesJson()
    return TrackerManager(
        myAnimeList = MyAnimeList(dependencies, json),
        aniList = Anilist(dependencies, json),
        kitsu = Kitsu(dependencies, json, lazy { mockk(relaxed = true) }),
        shikimori = Shikimori(dependencies, json),
        bangumi = Bangumi(dependencies, json),
        komga = Komga(dependencies, json),
        mangaUpdates = MangaUpdates(dependencies, json),
        kavita = Kavita(dependencies, json, lazy { mockk(relaxed = true) }),
        suwayomi = Suwayomi(dependencies, lazy { mockk(relaxed = true) }),
        hikka = Hikka(dependencies, json),
        mangaBaka = MangaBaka(dependencies, json),
        comick = Comick(dependencies, json),
        ranobeDb = RanobeDb(dependencies, json),
        novelUpdates = NovelUpdates(dependencies, json, lazy { mockk(relaxed = true) }, lazy { mockk(relaxed = true) }),
        novelList = NovelList(dependencies, json),
        mdList = MdList(
            dependencies,
            lazy { mockk(relaxed = true) },
            lazy { mockk(relaxed = true) },
            lazy { mockk(relaxed = true) },
        ),
    )
}

/**
 * Code that still resolves through Injekt (the built-in adult sources, as an extension would) needs a
 * graph standing in for the app's, installed the way the app installs its own: through the Injekt
 * registrar. [configure] stubs whatever the test reaches. Returns the scope it replaced, which the
 * caller restores.
 */
fun installTrackerTestGraph(
    network: NetworkHelper = mockk(relaxed = true),
    preferences: PreferenceStore = InMemoryPreferenceStore(),
    configure: AppGraph.() -> Unit = {},
): InjektScope {
    val graph = mockk<AppGraph>(relaxed = true) {
        every { trackPreferences } returns TrackPreferences(preferences)
        every { networkHelper } returns network
        every { json } returns AppBindings.providesJson()
        configure()
    }
    val application = mockk<Application>(relaxed = true, moreInterfaces = arrayOf(GraphProvider::class))
    @Suppress("UNCHECKED_CAST")
    every { (application as GraphProvider<AppGraph>).graph } returns graph
    every { application.applicationContext } returns application
    val replaced = Injekt
    Injekt = InjektScope(MetroInjektRegistrar(application, application as GraphProvider<AppGraph>))
    return replaced
}
