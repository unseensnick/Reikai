package reikai.domain.recommendation.taste

import eu.kanade.tachiyomi.data.track.bangumi.dto.BGMCollectionsResult
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuLibraryResult
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALLibraryResult
import eu.kanade.tachiyomi.data.track.shikimori.dto.SMUserRatesResponse
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Contract tests for the R4b tracker-library pull DTOs: guard the genres + status + score + id
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
    fun `Kitsu JSON-API envelope parses data, included and links`() {
        val payload = """
            {"data":[{"id":100,"attributes":{"status":"current","ratingTwenty":16},
                "relationships":{"manga":{"data":{"id":30,"type":"manga"}}}}],
             "included":[
               {"id":30,"type":"manga","attributes":{"canonicalTitle":"Berserk"},
                "relationships":{"categories":{"data":[{"id":5,"type":"categories"}]}}},
               {"id":5,"type":"categories","attributes":{"title":"Dark Fantasy"}}
             ],
             "links":{"next":"https://kitsu.app/next"}}
        """.trimIndent()

        val page = json.decodeFromString<KitsuLibraryResult>(payload)
        page.data.single().relationships.manga?.data?.id shouldBe 30L
        page.data.single().attributes.ratingTwenty shouldBe 16
        page.included.first { it.type == "categories" }.attributes.title shouldBe "Dark Fantasy"
        page.links.next shouldBe "https://kitsu.app/next"
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
