package exh.util

import exh.metadata.metadata.EHentaiSearchMetadata
import exh.metadata.metadata.base.RaisedTag
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The language text the E-Hentai list view and the details page both show. */
class EhLanguageLabelTest {

    private fun gallery(vararg languageTags: String) = EHentaiSearchMetadata().apply {
        languageTags.forEach {
            tags += RaisedTag(EHentaiSearchMetadata.EH_LANGUAGE_NAMESPACE, it, EHentaiSearchMetadata.TAG_TYPE_NORMAL)
        }
    }

    @Test
    fun `a translated gallery in a language with no known flag says so after its name`() {
        SourceTagsUtil.ehLanguageLabel(gallery("klingon"), "Klingon", "Translated") shouldBe "Klingon (Translated)"
    }

    @Test
    fun `a gallery in a known language leads with its flag`() {
        SourceTagsUtil.ehLanguageLabel(gallery("english"), "English", null) shouldBe "🇺🇸 English"
    }
}
