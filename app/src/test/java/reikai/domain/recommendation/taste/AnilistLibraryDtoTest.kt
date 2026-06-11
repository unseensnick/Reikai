package reikai.domain.recommendation.taste

import eu.kanade.tachiyomi.data.track.anilist.dto.ALUserLibraryResult
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Contract test for the AniList `MediaListCollection` (library pull) payload: guards the genres +
 * tag-name + status + scoreRaw field mappings the taste profile depends on. Parser mirrors the app's
 * production [Json] config.
 */
class AnilistLibraryDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `library payload flattens lists into entries with genres, tags, status and score`() {
        val payload = """
            {"data":{"MediaListCollection":{"lists":[
              {"entries":[
                {"status":"CURRENT","scoreRaw":85,"media":{
                  "id":30002,"idMal":2,"title":{"userPreferred":"Berserk"},
                  "genres":["Action","Horror"],"tags":[{"name":"Dark Fantasy"},{"name":"Gore"}]}}
              ]},
              {"entries":[
                {"status":"COMPLETED","scoreRaw":0,"media":{
                  "id":30013,"title":{"userPreferred":"One Piece"},
                  "genres":["Adventure"],"tags":[]}}
              ]}
            ]}}}
        """.trimIndent()

        val entries = json.decodeFromString<ALUserLibraryResult>(payload)
            .data.mediaListCollection.lists.flatMap { it.entries }

        entries.map { it.media.id } shouldContainExactly listOf(30002L, 30013L)
        val berserk = entries.first()
        berserk.status shouldBe "CURRENT"
        berserk.scoreRaw shouldBe 85
        berserk.media.idMal shouldBe 2L
        berserk.media.genres shouldContainExactly listOf("Action", "Horror")
        berserk.media.tags.map { it.name } shouldContainExactly listOf("Dark Fantasy", "Gore")
        // idMal absent -> null (the cross-tracker dedup key just stays empty for that entry).
        entries[1].media.idMal shouldBe null
    }
}
