package eu.kanade.tachiyomi.data.track.novellist

import eu.kanade.tachiyomi.data.track.novellist.dto.NLNovel
import eu.kanade.tachiyomi.data.track.novellist.dto.NLReadingListEntry
import eu.kanade.tachiyomi.data.track.novellist.dto.NLUpdateRequest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.serializer
import mihon.app.di.AppBindings
import org.junit.jupiter.api.Test

/**
 * Pins the Novellist API schema. The service publishes an OpenAPI document but no human-facing docs
 * and no versioning promise, and its backend answers on a generated hostname, so these fixtures are
 * the only thing that would notice the shape moving. Payloads are trimmed from live responses.
 */
class NovelListDtoTest {

    // The graph-wide instance, not a copy of its settings: whether a null default reaches the wire is
    // decided there, and a local Json would stay green while production wiped the user's notes.
    private val json = AppBindings.providesJson()

    @Test
    fun `a search result parses the uuid, chapter count and cover`() {
        val payload = """
            [
              {
                "id": "019743f3-4576-7e30-92ef-5078eb186a6c",
                "slug": "mushoku-tensei-ln",
                "raw_title": "無職転生",
                "english_title": "Mushoku Tensei (LN)",
                "alternate_titles": ["Jobless Reincarnation"],
                "description": "A 34-year-old NEET.",
                "language": "JAPANESE",
                "status": "COMPLETED",
                "cover_image_link": "https://bucket.novellist.co/a.webp",
                "chapter_count": 286,
                "view_count": 900,
                "labels": [{ "id": 303, "type": "TAG", "name": "Reincarnation" }]
              }
            ]
        """.trimIndent()

        val novels = json.decodeFromString<List<NLNovel>>(payload)

        novels.single().id shouldBe "019743f3-4576-7e30-92ef-5078eb186a6c"
        novels.single().chapterCount shouldBe 286L
    }

    /** The schema marks these nullable even though a live search returned no null in 90 records. */
    @Test
    fun `a novel with null collections and no chapter count still parses`() {
        val payload = """
            {
              "id": "019743f3-4040-75a9-a141-7e1b0c66e8c2",
              "slug": "a-novel",
              "english_title": "A Novel",
              "alternate_titles": null,
              "labels": null,
              "chapter_count": null
            }
        """.trimIndent()

        json.decodeFromString<NLNovel>(payload).chapterCount shouldBe null
    }

    /** Only `GET /novels/{id}` carries the author; the filter response omits the key entirely. */
    @Test
    fun `the single-novel shape parses the author the search omits`() {
        val payload = """
            {
              "id": "019743f3-4040-75a9-a141-7e1b0c66e8c2",
              "slug": "mushoku-tensei-ln",
              "english_title": "Mushoku Tensei (LN)",
              "author": { "id": "019743f3", "name": "Rifujin na Magonote", "slug": "rifujin" }
            }
        """.trimIndent()

        json.decodeFromString<NLNovel>(payload).author?.name shouldBe "Rifujin na Magonote"
    }

    @Test
    fun `a list entry parses the note that a careless write would destroy`() {
        val payload = """
            {
              "chapter_count": 42,
              "created_at": "2026-08-25T10:00:00Z",
              "note": "re-reading before the sequel",
              "rating": 8.5,
              "status": "IN_PROGRESS"
            }
        """.trimIndent()

        val entry = json.decodeFromString<NLReadingListEntry>(payload)

        entry.note shouldBe "re-reading before the sequel"
        entry.rating shouldBe 8.5
    }

    /** A null means "leave alone" to this route, so it must not reach the wire as an explicit null. */
    @Test
    fun `an update omits the fields it is not changing`() {
        val body = json.encodeToString(NLUpdateRequest(chapterCount = 12, status = "IN_PROGRESS"))

        body shouldContain "\"status\":\"IN_PROGRESS\""
        body shouldNotContain "note"
        body shouldNotContain "rating"
    }

    /**
     * The service resets progress to 0 for any body without `chapter_count`, so a status-only or
     * score-only edit would wipe it. Measured live; nothing in their document hints at it.
     */
    @Test
    fun `every update carries the chapter count, even a score-only edit`() {
        val scoreEdit = json.encodeToString(NLUpdateRequest(chapterCount = 7, rating = 9.0))

        scoreEdit shouldContain "\"chapter_count\":7"
        // The serialized shape above only holds while the field stays mandatory. Give it a default
        // and a caller could leave it out, which is the write that wipes the user's progress.
        val descriptor = NLUpdateRequest.serializer().descriptor
        descriptor.isElementOptional(descriptor.getElementIndex("chapter_count")) shouldBe false
    }
}
