package eu.kanade.tachiyomi.data.track.comick

import eu.kanade.tachiyomi.data.track.comick.dto.ComickComicDetails
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/** The creators Fill from tracker takes from Comick's public comic page, in the shape it answers. */
class ComickComicDetailsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(body: String) = json.decodeFromString<ComickComicDetails>(body)

    @Test
    fun `authors are joined as the other trackers join them`() {
        parse(
            """{"comic":{"hid":"x"},"authors":[{"name":"Oda Eiichiro","slug":"oda"},{"name":"Second","slug":"s"}]}""",
        ).authorNames() shouldBe "Oda Eiichiro, Second"
    }

    @Test
    fun `artists come from their own list`() {
        parse("""{"authors":[{"name":"Writer"}],"artists":[{"name":"Drawer"}]}""").artistNames() shouldBe "Drawer"
    }

    @Test
    fun `a page with no creators fills none rather than an empty name`() {
        parse("""{"authors":[{"name":" "}],"artists":[]}""").authorNames() shouldBe null
    }
}
