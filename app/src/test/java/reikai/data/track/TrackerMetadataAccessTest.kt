package reikai.data.track

import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.bangumi.Bangumi
import eu.kanade.tachiyomi.data.track.comick.Comick
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class AccessCase(private val label: String, val tracker: () -> Tracker, val expected: MetadataAccess) {
    override fun toString() = label
}

/**
 * Which trackers fill from tracker while signed out: those whose metadata call goes through a public
 * client. The rest fetch it on their login client, so a signed-out fill is refused before it runs.
 */
class TrackerMetadataAccessTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `each tracker declares whether its metadata needs a login`(case: AccessCase) {
        case.tracker().metadataAccess shouldBe case.expected
    }

    companion object {
        @JvmStatic
        fun cases() = listOf(
            AccessCase("MangaUpdates", { trackerTestManager().mangaUpdates }, MetadataAccess.Public),
            AccessCase("MdList", { trackerTestManager().mdList }, MetadataAccess.Public),
            AccessCase("RanobeDb", { trackerTestManager().ranobeDb }, MetadataAccess.Public),
            AccessCase("NovelList", { trackerTestManager().novelList }, MetadataAccess.Public),
            AccessCase("NovelUpdates", { trackerTestManager().novelUpdates }, MetadataAccess.Public),
            AccessCase("Anilist", { trackerTestManager().aniList }, MetadataAccess.SignedIn),
            AccessCase("Kitsu", { trackerTestManager().kitsu }, MetadataAccess.SignedIn),
            AccessCase("Shikimori", { trackerTestManager().shikimori }, MetadataAccess.SignedIn),
            AccessCase("MyAnimeList", { trackerTestManager().myAnimeList }, MetadataAccess.SignedIn),
            AccessCase("Bangumi", { trackerTestManager().bangumi }, MetadataAccess.SignedIn),
            AccessCase("MangaBaka", { trackerTestManager().mangaBaka }, MetadataAccess.SignedIn),
            AccessCase("Hikka", { trackerTestManager().hikka }, MetadataAccess.SignedIn),
            AccessCase("Comick", { trackerTestManager().comick }, MetadataAccess.SignedIn),
        )
    }
}
