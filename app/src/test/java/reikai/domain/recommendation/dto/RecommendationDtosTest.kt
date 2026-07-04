package reikai.domain.recommendation.dto

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Contract tests for the tracker recommendation response DTOs. These guard the re-typed
 * `@SerialName` mappings against real (extra-field-carrying) payloads, the main risk when porting
 * the providers. The parser mirrors the app's production [Json] config.
 */
class RecommendationDtosTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `AniList recommendations payload maps to titles, synonyms and cover`() {
        val payload = """
            {"data":{"Page":{"media":[{
              "title":{"romaji":"Berserk","english":"Berserk","native":"ベルセルク"},
              "synonyms":["Berserk Deluxe"],
              "recommendations":{"edges":[
                {"node":{"mediaRecommendation":{
                  "countryOfOrigin":"JP",
                  "siteUrl":"https://anilist.co/manga/31706",
                  "title":{"romaji":"Vinland Saga","english":"Vinland Saga","native":"ヴィンランド・サガ"},
                  "synonyms":["VS"],
                  "coverImage":{"large":"https://img/large.jpg"}
                }}}
              ]}
            }]}}}
        """.trimIndent()

        val rec = json.decodeFromString<ALRecsResponse>(payload)
            .data.page.media.single()
            .recommendations!!.edges.single()
            .node!!.mediaRecommendation!!

        rec.siteUrl shouldBe "https://anilist.co/manga/31706"
        rec.title?.english shouldBe "Vinland Saga"
        rec.synonyms shouldBe listOf("VS")
        rec.coverImage?.large shouldBe "https://img/large.jpg"
    }

    @Test
    fun `Jikan recommendations payload maps entry url, title and webp image`() {
        val payload = """
            {"data":[
              {"entry":{"mal_id":2,"title":"Berserk","url":"https://myanimelist.net/manga/2",
                "images":{"webp":{"image_url":"https://img/webp.webp"},"jpg":{"image_url":"https://img/jpg.jpg"}}}},
              {"entry":{"mal_id":21,"title":"Death Note","url":"https://myanimelist.net/manga/21"}}
            ]}
        """.trimIndent()

        val data = json.decodeFromString<JikanRecsResponse>(payload).data
        data shouldHaveSize 2
        data.first().entry.title shouldBe "Berserk"
        data.first().entry.images?.webp?.imageUrl shouldBe "https://img/webp.webp"
        data[1].entry.images shouldBe null
    }

    @Test
    fun `MangaUpdates series payload maps recommendation name, url and original image`() {
        val payload = """
            {"recommendations":[
              {"series_name":"Vinland Saga","series_url":"https://mangaupdates.com/series/abc",
               "series_image":{"url":{"original":"https://img/original.jpg"}}}
            ]}
        """.trimIndent()

        val rec = json.decodeFromString<MUSeriesResponse>(payload).recommendations.single()
        rec.seriesName shouldBe "Vinland Saga"
        rec.seriesUrl shouldBe "https://mangaupdates.com/series/abc"
        rec.seriesImage?.url?.original shouldBe "https://img/original.jpg"
    }

    @Test
    fun `MangaUpdates series payload maps the category_recommendations bucket too`() {
        // The similar-titles bucket carries the same item shape as the human recommendations one.
        val payload = """
            {"recommendations":[
              {"series_name":"Vinland Saga","series_url":"https://mangaupdates.com/series/abc"}
            ],
            "category_recommendations":[
              {"series_name":"Vagabond","series_url":"https://mangaupdates.com/series/xyz",
               "series_image":{"url":{"original":"https://img/similar.jpg"}}}
            ]}
        """.trimIndent()

        val rec = json.decodeFromString<MUSeriesResponse>(payload).categoryRecommendations.single()
        rec.seriesName shouldBe "Vagabond"
        rec.seriesImage?.url?.original shouldBe "https://img/similar.jpg"
    }

    @Test
    fun `MangaUpdates search payload maps the first series id`() {
        val payload = """{"results":[{"record":{"series_id":12345}},{"record":{"series_id":67890}}]}"""
        val id = json.decodeFromString<MUSearchResponse>(payload).results.first().record.seriesId
        id shouldBe 12345L
    }

    @Test
    fun `Shikimori similar payload maps relative url and image`() {
        // The compact manga objects carry many fields the carousel ignores; only id, name, url, image.
        val payload = """
            [
              {"id":1,"name":"Vinland Saga","russian":"Сага о Винланде","url":"/mangas/1-vinland-saga",
               "kind":"manga","score":"9.0","status":"released",
               "image":{"original":"/uploads/manga/original/1.jpg","preview":"/uploads/manga/preview/1.jpg"}}
            ]
        """.trimIndent()

        val manga = json.decodeFromString<List<SMRecsManga>>(payload).single()
        manga.id shouldBe 1L
        manga.name shouldBe "Vinland Saga"
        manga.url shouldBe "/mangas/1-vinland-saga"
        manga.image?.original shouldBe "/uploads/manga/original/1.jpg"
    }
}
