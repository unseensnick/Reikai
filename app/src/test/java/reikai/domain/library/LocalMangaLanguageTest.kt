package reikai.domain.library

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nl.adaptivity.xmlutil.serialization.XML
import org.junit.jupiter.api.Test
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.core.metadata.comicinfo.LOCAL_LANGUAGE_MEMO_KEY
import tachiyomi.core.metadata.comicinfo.copyFromComicInfo
import tachiyomi.core.metadata.comicinfo.getComicInfo
import tachiyomi.core.metadata.comicinfo.localLanguage
import tachiyomi.core.metadata.comicinfo.setLocalLanguage
import tachiyomi.domain.manga.model.Manga

/** A local manga's language: read from ComicInfo into its memo, kept through a rewrite, read by the rows. */
class LocalMangaLanguageTest {

    // The app's own reader, as LocalSource is given it.
    private val xml = XML.v1 {
        policy {
            ignoreUnknownChildren()
            autoPolymorphic = true
        }
    }

    // Built rather than parsed: the JVM has no Android XML parser. The writing test pins the element name.
    private fun comicInfo(language: String?) = SManga.create().apply {
        title = "Book"
        setLocalLanguage(language)
    }.getComicInfo()

    @Test
    fun `a ComicInfo's LanguageISO becomes the series' language`() {
        val manga = SManga.create().apply { copyFromComicInfo(comicInfo("ja")) }

        manga.localLanguage shouldBe "ja"
    }

    @Test
    fun `a ComicInfo that names no language drops one read before`() {
        val manga = SManga.create().apply { setLocalLanguage("ja") }

        manga.copyFromComicInfo(comicInfo(null))

        manga.localLanguage shouldBe null
    }

    /** details.json is converted into a ComicInfo.xml, so its language has to survive the rewrite. */
    @Test
    fun `a series' language is written back into its ComicInfo`() {
        val manga = SManga.create().apply {
            title = "Book"
            setLocalLanguage("ko")
        }

        xml.encodeToString(ComicInfo.serializer(), manga.getComicInfo()) shouldContain "<LanguageISO>ko</LanguageISO>"
    }

    @Test
    fun `a library row reads the language as sources write it`() {
        val manga = Manga.create().copy(memo = JsonObject(mapOf(LOCAL_LANGUAGE_MEMO_KEY to JsonPrimitive("en-US"))))

        manga.localFilesLanguage shouldBe "en"
    }
}
