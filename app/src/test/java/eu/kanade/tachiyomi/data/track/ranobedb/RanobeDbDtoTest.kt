package eu.kanade.tachiyomi.data.track.ranobedb

import eu.kanade.tachiyomi.data.track.ranobedb.dto.RDBSeriesList
import eu.kanade.tachiyomi.data.track.ranobedb.dto.RDBSeriesListEntry
import eu.kanade.tachiyomi.data.track.ranobedb.dto.RDBSeriesOne
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Pins the RanobeDB `/api/v0` schema. The write routes under `/api/v0/user/` are merged and live
 * but deliberately undocumented (the published docs still describe the API as read-only), so an
 * undocumented surface gets no deprecation notice and these fixtures are the only thing that
 * notices it moving. Payloads mirror the shapes in the server's own zod schema.
 */
class RanobeDbDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `series search parses the id, volume count and nested cover`() {
        val payload = """
            {
              "series": [
                {
                  "id": 1234,
                  "title": "Seishun Buta Yarou",
                  "romaji": "Seishun Buta Yarou",
                  "title_orig": "青春ブタ野郎",
                  "romaji_orig": null,
                  "hidden": false,
                  "locked": false,
                  "lang": "en",
                  "olang": "ja",
                  "c_num_books": 13,
                  "volumes": { "count": "13" },
                  "book": { "id": 99, "image": { "id": 5, "filename": "abc.jpg" } }
                }
              ],
              "count": "1",
              "currentPage": 1,
              "totalPages": 1
            }
        """.trimIndent()

        val series = json.decodeFromString<RDBSeriesList>(payload).series.single()

        series.id shouldBe 1234L
        series.volumeCount shouldBe 13L
        series.book?.image?.filename shouldBe "abc.jpg"
    }

    @Test
    fun `series detail parses staff roles, genre tags and publication status`() {
        val payload = """
            {
              "series": {
                "id": 1234,
                "title": "Seishun Buta Yarou",
                "description": "A story.",
                "publication_status": "ongoing",
                "c_num_books": 13,
                "books": [
                  { "sort_order": 2, "image": { "filename": "vol2.jpg" } },
                  { "sort_order": 1, "image": { "filename": "vol1.jpg" } }
                ],
                "staff": [
                  { "name": "Hajime Kamoshida", "role_type": "author" },
                  { "name": "Keji Mizoguchi", "role_type": "artist" }
                ],
                "tags": [
                  { "name": "Romance", "ttype": "genre" },
                  { "name": "School", "ttype": "tag" }
                ]
              }
            }
        """.trimIndent()

        val series = json.decodeFromString<RDBSeriesOne>(payload).series

        series.publicationStatus shouldBe "ongoing"
        series.staff.single { it.roleType == "author" }.name shouldBe "Hajime Kamoshida"
        series.tags.filter { it.ttype == "genre" }.map { it.name } shouldBe listOf("Romance")
        series.books.minByOrNull { it.sortOrder }?.image?.filename shouldBe "vol1.jpg"
    }

    @Test
    fun `series detail omits the cached volume count, so books is the only source`() {
        // getSeriesOne does not select c_num_books; only the list route does. Reading volumeCount
        // here would decode to 0 and wipe a total the search already found.
        val payload = """
            {
              "series": {
                "id": 1234,
                "title": "Seishun Buta Yarou",
                "books": [
                  { "sort_order": 1, "book_type": "main" },
                  { "sort_order": 2, "book_type": "main" }
                ]
              }
            }
        """.trimIndent()

        val series = json.decodeFromString<RDBSeriesOne>(payload).series

        series.volumeCount shouldBe 0L
        series.books.size shouldBe 2
    }

    @Test
    fun `write payload always carries the three lists the route requires`() {
        // langs, formats and selectedCustLabels have no server-side default, so the route rejects a
        // body that omits them. They must survive encoding even when empty.
        val entry = RDBSeriesListEntry(
            readingStatus = "Reading",
            score = null,
            volumesRead = null,
            started = null,
            finished = null,
            langs = emptyList(),
            formats = emptyList(),
            selectedCustLabels = emptyList(),
        )

        val encoded = json.encodeToString(entry)

        encoded shouldContain "\"langs\":[]"
        encoded shouldContain "\"formats\":[]"
        encoded shouldContain "\"selectedCustLabels\":[]"
    }

    @Test
    fun `write payload sends the score unscaled`() {
        // The server multiplies by 10 into its own 100-point storage, so pre-scaling would double it.
        val entry = RDBSeriesListEntry(
            readingStatus = "Finished",
            score = 8.0,
            volumesRead = 4,
            started = "2026-01-02",
            finished = "2026-03-04",
            langs = emptyList(),
            formats = emptyList(),
            selectedCustLabels = emptyList(),
        )

        json.encodeToString(entry) shouldContain "\"score\":8.0"
    }
}
