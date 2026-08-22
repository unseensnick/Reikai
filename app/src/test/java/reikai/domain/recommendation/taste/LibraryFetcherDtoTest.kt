package reikai.domain.recommendation.taste

import eu.kanade.tachiyomi.data.track.anilist.dto.ALUserLibraryResult
import eu.kanade.tachiyomi.data.track.bangumi.dto.BGMCollectionsResult
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuUserLibraryResult
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALLibraryResult
import eu.kanade.tachiyomi.data.track.shikimori.dto.SMUserRatesResponse
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Contract tests for the tracker-library pull DTOs: guard the genres + status + score + id
 * mappings the taste profile depends on, especially the net-new Shikimori GraphQL and Bangumi
 * shapes. Parser mirrors the app's production [Json] config.
 */
class LibraryFetcherDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `MAL mangalist maps node genres and list_status`() {
        val payload = """
            {"data":[
              {"node":{"id":2,"title":"Berserk","genres":[{"name":"Action"},{"name":"Horror"}]},
               "list_status":{"status":"reading","score":9,"is_rereading":false}}
            ],"paging":{"next":null}}
        """.trimIndent()

        val item = json.decodeFromString<MALLibraryResult>(payload).data.single()
        item.node.id shouldBe 2L
        item.node.genres.map { it.name } shouldContainExactly listOf("Action", "Horror")
        item.listStatus?.status shouldBe "reading"
        item.listStatus?.score shouldBe 9
    }

    @Test
    fun `Kitsu GraphQL library connection parses nodes, categories and mappings`() {
        val payload = """
            {"data":{"currentProfile":{"library":{"all":{
               "pageInfo":{"hasNextPage":true,"endCursor":"Mg"},
               "nodes":[{"status":"CURRENT","rating":16,"media":{
                 "id":"30",
                 "titles":{"preferred":"Berserk"},
                 "categories":{"nodes":[{"title":{"en":"Dark Fantasy"}}]},
                 "mappings":{"nodes":[
                   {"externalSite":"MYANIMELIST_MANGA","externalId":"2"},
                   {"externalSite":"ANILIST_MANGA","externalId":"30002"}
                 ]}
               }}]
            }}}}}
        """.trimIndent()

        val page = json.decodeFromString<KitsuUserLibraryResult>(payload)
        val connection = page.data.currentProfile!!.library.all
        val media = connection.nodes.single().media!!
        connection.nodes.single().status shouldBe "CURRENT"
        connection.nodes.single().rating shouldBe 16
        media.id shouldBe "30"
        media.titles.preferred shouldBe "Berserk"
        media.categories.nodes.single().title["en"] shouldBe "Dark Fantasy"
        media.mappings.nodes.map { it.externalSite } shouldContainExactly
            listOf("MYANIMELIST_MANGA", "ANILIST_MANGA")
        connection.pageInfo.endCursor shouldBe "Mg"
    }

    @Test
    fun `AniList library entry carries its own adult ruling`() {
        val payload = """
            {"data":{"MediaListCollection":{"lists":[{"entries":[
              {"status":"CURRENT","scoreRaw":90,"media":{
                "id":30002,"idMal":2,"title":{"userPreferred":"Berserk"},
                "genres":["Action"],"tags":[{"name":"Male Protagonist"}],"isAdult":false}},
              {"status":"COMPLETED","scoreRaw":70,"media":{
                "id":30003,"title":{"userPreferred":"Explicit"},
                "genres":["Hentai"],"tags":[],"isAdult":true}}
            ]}]}}}
        """.trimIndent()

        val entries = json.decodeFromString<ALUserLibraryResult>(payload)
            .data.mediaListCollection.lists.single().entries
        entries.map { it.media.isAdult } shouldContainExactly listOf(false, true)
    }

    @Test
    fun `Shikimori GraphQL userRates maps score, status and manga genres`() {
        val payload = """
            {"data":{"userRates":[
              {"score":8,"status":"watching","manga":{"id":"42","name":"Berserk","genres":[{"name":"Seinen"}]}}
            ]}}
        """.trimIndent()

        val rate = json.decodeFromString<SMUserRatesResponse>(payload).data.userRates.single()
        rate.score shouldBe 8
        rate.status shouldBe "watching"
        rate.manga?.id shouldBe "42"
        rate.manga?.genres?.map { it.name } shouldContainExactly listOf("Seinen")
    }

    @Test
    fun `Bangumi collections maps type, rate and subject tags`() {
        val payload = """
            {"data":[
              {"subject_id":77,"type":3,"rate":7,
               "subject":{"id":77,"name":"Berserk","name_cn":"剑风传奇","tags":[{"name":"奇幻"},{"name":"热血"}]}}
            ],"total":1}
        """.trimIndent()

        val item = json.decodeFromString<BGMCollectionsResult>(payload).data.single()
        item.subjectId shouldBe 77L
        item.type shouldBe 3
        item.rate shouldBe 7
        item.subject?.nameCn shouldBe "剑风传奇"
        item.subject?.tags?.map { it.name } shouldContainExactly listOf("奇幻", "热血")
    }
}
