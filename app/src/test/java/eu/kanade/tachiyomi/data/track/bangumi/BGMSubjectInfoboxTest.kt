package eu.kanade.tachiyomi.data.track.bangumi

import eu.kanade.tachiyomi.data.track.bangumi.dto.BGMSubject
import eu.kanade.tachiyomi.data.track.bangumi.dto.Infobox
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Guards the Bangumi subject-detail parse that "Fill from tracker" relies on. The `infobox` field is
 * polymorphic (a value is either a string or a nested list), so a custom serializer picks SingleValue
 * vs MultipleValues; this test locks that split and the author/illustrator key extraction (作者 / 插画).
 */
class BGMSubjectInfoboxTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `subject parses mixed infobox and exposes single-value author and illustrator`() {
        val payload = """
            {
              "id": 12,
              "name": "ベルセルク",
              "name_cn": "剑风传奇",
              "summary": "Guts, a former mercenary...",
              "date": "1990-08-25",
              "images": {"common": "https://lain.bgm.tv/pic/cover/c/berserk.jpg"},
              "rating": {"score": 8.9},
              "platform": "漫画",
              "infobox": [
                {"key": "中文名", "value": "剑风传奇"},
                {"key": "作者", "value": "三浦建太郎"},
                {"key": "插画", "value": "三浦建太郎"},
                {"key": "别名", "value": [{"k": "罗马字", "v": "Berserk"}, {"v": "烙印勇士"}]}
              ]
            }
        """.trimIndent()

        val subject = json.decodeFromString<BGMSubject>(payload)

        subject.id shouldBe 12L
        subject.nameCn shouldBe "剑风传奇"
        subject.images?.common shouldBe "https://lain.bgm.tv/pic/cover/c/berserk.jpg"

        val author = subject.infobox.filterIsInstance<Infobox.SingleValue>().first { "作者" in it.key }
        author.value shouldBe "三浦建太郎"

        val illustrator = subject.infobox.filterIsInstance<Infobox.SingleValue>().first { "插画" in it.key }
        illustrator.value shouldBe "三浦建太郎"

        // The list-valued entry deserializes as MultipleValues (not SingleValue), so it is skipped by
        // the author/artist extraction rather than crashing the parse.
        val aliases = subject.infobox.filterIsInstance<Infobox.MultipleValues>().first { "别名" in it.key }
        aliases.value.map { it.value } shouldBe listOf("Berserk", "烙印勇士")
    }
}
