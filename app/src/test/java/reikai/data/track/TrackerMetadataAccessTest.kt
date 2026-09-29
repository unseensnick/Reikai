package reikai.data.track

import android.app.Application
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.bangumi.Bangumi
import eu.kanade.tachiyomi.data.track.hikka.Hikka
import eu.kanade.tachiyomi.data.track.kitsu.Kitsu
import eu.kanade.tachiyomi.data.track.mangabaka.MangaBaka
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdates
import eu.kanade.tachiyomi.data.track.mdlist.MdList
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.data.track.novellist.NovelList
import eu.kanade.tachiyomi.data.track.novelupdates.NovelUpdates
import eu.kanade.tachiyomi.data.track.ranobedb.RanobeDb
import eu.kanade.tachiyomi.data.track.shikimori.Shikimori
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import mihon.app.di.AppGraph
import mihon.app.di.injekt.MetroInjektRegistrar
import mihon.core.metro.GraphProvider
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope

class AccessCase(private val label: String, val tracker: () -> Tracker, val expected: MetadataAccess) {
    override fun toString() = label
}

/**
 * Which trackers fill from tracker while signed out: those whose metadata call goes through a public
 * client. The rest fetch it on their login client, so a signed-out fill is refused before it runs.
 * Some trackers read their preferences through the app graph as they are built, so a graph standing
 * in for the app's is installed the way the app installs its own, through the Injekt registrar.
 */
class TrackerMetadataAccessTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `each tracker declares whether its metadata needs a login`(case: AccessCase) {
        case.tracker().metadataAccess shouldBe case.expected
    }

    companion object {
        private lateinit var appScope: InjektScope

        @JvmStatic
        @BeforeAll
        fun installGraph() {
            val graph = mockk<AppGraph>(relaxed = true) {
                every { trackPreferences } returns TrackPreferences(InMemoryPreferenceStore())
            }
            val application = mockk<Application>(relaxed = true, moreInterfaces = arrayOf(GraphProvider::class))
            @Suppress("UNCHECKED_CAST")
            every { (application as GraphProvider<AppGraph>).graph } returns graph
            every { application.applicationContext } returns application
            appScope = Injekt
            Injekt = InjektScope(MetroInjektRegistrar(application, application as GraphProvider<AppGraph>))
        }

        @JvmStatic
        @AfterAll
        fun restoreGraph() {
            Injekt = appScope
        }

        @JvmStatic
        fun cases() = listOf(
            AccessCase("MangaUpdates", { MangaUpdates(7) }, MetadataAccess.Public),
            AccessCase("MdList", { MdList(60) }, MetadataAccess.Public),
            AccessCase("RanobeDb", { RanobeDb(100) }, MetadataAccess.Public),
            AccessCase("NovelList", { NovelList(101) }, MetadataAccess.Public),
            AccessCase("NovelUpdates", { NovelUpdates(102) }, MetadataAccess.Public),
            AccessCase("Anilist", { Anilist(2) }, MetadataAccess.SignedIn),
            AccessCase("Kitsu", { Kitsu(3) }, MetadataAccess.SignedIn),
            AccessCase("Shikimori", { Shikimori(4) }, MetadataAccess.SignedIn),
            AccessCase("MyAnimeList", { MyAnimeList(1) }, MetadataAccess.SignedIn),
            AccessCase("Bangumi", { Bangumi(5) }, MetadataAccess.SignedIn),
            AccessCase("MangaBaka", { MangaBaka(9) }, MetadataAccess.SignedIn),
            AccessCase("Hikka", { Hikka(10) }, MetadataAccess.SignedIn),
        )
    }
}
