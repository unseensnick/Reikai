package eu.kanade.tachiyomi.data.track

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
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
import kotlinx.coroutines.flow.combine

@Inject
@SingleIn(AppScope::class)
class TrackerManager(
    val myAnimeList: MyAnimeList,
    val aniList: Anilist,
    val kitsu: Kitsu,
    val shikimori: Shikimori,
    val bangumi: Bangumi,
    val komga: Komga,
    val mangaUpdates: MangaUpdates,
    val kavita: Kavita,
    val suwayomi: Suwayomi,
    val hikka: Hikka,
    val mangaBaka: MangaBaka,
    val comick: Comick,
    // RK -->
    // RanobeDB light-novel tracker (paste-a-token login, binds to a series)
    val ranobeDb: RanobeDb,
    // NovelUpdates light-novel tracker (browser sign-in, scraped, progress lives in notes)
    val novelUpdates: NovelUpdates,
    // NovelList light-novel tracker (browser sign-in, UUID ids)
    val novelList: NovelList,
    // MangaDex MDList tracker (OAuth login + follow-status/rating sync)
    val mdList: MdList,
    // RK <--
) {

    companion object { // RK: ids for Reikai's own trackers
        // RK: MangaDex MDList tracker (id matches Komikku; ids persist with tracks, never change it)
        const val MDLIST = 60L

        // RK: light-novel trackers (ids match tsundoku; ids persist with tracks, never change one)
        const val NOVELUPDATES = 100L
        const val NOVELLIST = 101L
        const val RANOBEDB = 102L
    }

    val trackers = listOf(
        myAnimeList,
        aniList,
        kitsu,
        shikimori,
        bangumi,
        komga,
        mangaUpdates,
        kavita,
        suwayomi,
        hikka,
        mangaBaka, // RK: kept after Hikka, where upstream lists it first
        comick,
        // RK -->
        mdList,
        ranobeDb,
        novelList,
        novelUpdates,
        // RK <--
    )

    fun loggedInTrackers() = trackers.filter { it.isLoggedIn }

    fun loggedInTrackersFlow() = combine(trackers.map { it.isLoggedInFlow }) {
        it.mapIndexedNotNull { index, isLoggedIn ->
            if (isLoggedIn) trackers[index] else null
        }
    }

    fun get(id: Long) = trackers.find { it.id == id }

    fun getAll(ids: Set<Long>) = trackers.filter { it.id in ids }
}
