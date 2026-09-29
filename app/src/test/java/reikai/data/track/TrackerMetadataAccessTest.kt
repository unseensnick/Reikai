package reikai.data.track

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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope

class AccessCase(private val label: String, val tracker: () -> Tracker, val expected: MetadataAccess) {
    override fun toString() = label
}

/**
 * Which trackers fill from tracker while signed out: those whose metadata call goes through a public
 * client. The rest fetch it on their login client, so a signed-out fill is refused before it runs.
 * Some trackers read their preferences through the app graph as they are built ([installTrackerTestGraph]).
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
            appScope = installTrackerTestGraph()
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
