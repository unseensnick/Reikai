package eu.kanade.tachiyomi.data.track.hikka

import eu.kanade.tachiyomi.data.track.hikka.dto.HKManga
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Guards the Hikka `/manga/{slug}` parse that "Fill from tracker" relies on. Hikka has no Komikku
 * reference, so this locks the net-new fields the mapper reads: synopsis, credited people split by
 * role name (Story/Art), and genres. Field names verified against the live openapi.json.
 */
class HKMangaMetadataTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `manga detail parses synopsis, role-tagged authors and genres`() {
        val payload = """
            {
              "data_type": "manga",
              "title_original": "Berserk",
              "media_type": "manga",
              "title_ua": "Берсерк",
              "title_en": "Berserk",
              "translated_ua": false,
              "status": "publishing",
              "image": "https://cdn.hikka.io/berserk.jpg",
              "scored_by": 1000,
              "score": 9.4,
              "slug": "berserk-fb9fbd",
              "synopsis_en": "Guts, known as [the Black Swordsman](https://hikka.io/x), roams the land.",
              "synopsis_ua": "Ґатс блукає землями.",
              "authors": [
                {"person": {"name_en": "Kentarou Miura", "name_native": "三浦建太郎"}, "roles": [{"slug": "story", "name_en": "Story"}]},
                {"person": {"name_en": "Kentarou Miura", "name_native": "三浦建太郎"}, "roles": [{"slug": "art", "name_en": "Art"}]},
                {"person": {"name_en": "Studio Gaga"}, "roles": [{"slug": "art", "name_en": "Art"}]}
              ],
              "genres": [
                {"name_en": "Action", "name_ua": "Екшн", "slug": "action", "type": "genre"},
                {"name_en": "Gore", "name_ua": "Ґор", "slug": "gore", "type": "theme"}
              ]
            }
        """.trimIndent()

        val manga = json.decodeFromString<HKManga>(payload)

        manga.slug shouldBe "berserk-fb9fbd"
        manga.synopsisUa shouldBe "Ґатс блукає землями."

        // Story vs Art split on the role's name_en, mirroring AniList/MAL.
        manga.authors
            .filter { a -> a.roles.any { it.nameEn == "Story" } }
            .mapNotNull { it.person?.nameEn } shouldContainExactly listOf("Kentarou Miura")
        manga.authors
            .filter { a -> a.roles.any { it.nameEn == "Art" } }
            .mapNotNull { it.person?.nameEn } shouldContainExactly listOf("Kentarou Miura", "Studio Gaga")

        manga.genres.mapNotNull { it.nameEn } shouldContainExactly listOf("Action", "Gore")
    }
}
