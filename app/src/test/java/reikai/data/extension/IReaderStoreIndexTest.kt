package reikai.data.extension

import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import mihon.domain.extension.model.ContentWarning
import mihon.domain.extension.model.ExtensionStore
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import reikai.domain.extension.NO_SIGNING_KEY

/** IReader's store index, read as a keyless store of IReader novel extensions. */
class IReaderStoreIndexTest {

    @Test
    fun `an extension's apk and icon are named after its apk file`() {
        val extension = extensions(FREE_WEB_NOVEL).single()
        (extension.apkUrl to extension.iconUrl) shouldBe (
            "$BASE/apk/ireader-en-freewebnovel-v2.17.apk" to "$BASE/icon/ireader-en-freewebnovel-v2.17.png"
            )
    }

    @Test
    fun `an extension's library version is its version name without the build`() {
        extensions(FREE_WEB_NOVEL).single().libVersion shouldBe 2.0
    }

    @Test
    fun `an extension lists as an IReader novel extension under its source id`() {
        val extension = extensions(FREE_WEB_NOVEL).single()
        (extension.kind to extension.sources.single().id) shouldBe (Extension.Kind.IREADER to 3970612267773145070L)
    }

    @Test
    fun `an nsfw extension carries the warning`() {
        extensions(FREE_WEB_NOVEL.replace("\"nsfw\":false", "\"nsfw\":true")).single().contentWarning shouldBe
            ContentWarning.NSFW
    }

    @Test
    fun `the newest copy of a package is listed when a stale one follows it`() {
        extensions(NOVEL_FULL_NEW, NOVEL_FULL_OLD).single().versionCode shouldBe 9L
    }

    @Test
    fun `the newest copy of a package is listed when a stale one precedes it`() {
        extensions(NOVEL_FULL_OLD, NOVEL_FULL_NEW).single().versionCode shouldBe 9L
    }

    @Test
    fun `IReader's own index is a store with its key named after its owner`() {
        readIReaderStore(INDEX_URL, source(FREE_WEB_NOVEL), json) shouldBe store
    }

    @Test
    fun `another IReader-format index is a keyless store`() {
        readIReaderStore(OTHER_INDEX_URL, source(FREE_WEB_NOVEL), json)?.signingKey shouldBe NO_SIGNING_KEY
    }

    @Test
    fun `a tachiyomi index is not read as an IReader store`() {
        readIReaderStore(INDEX_URL, source(TACHIYOMI_ENTRY), json).shouldBeNull()
    }

    @Test
    fun `an empty index is not a store`() {
        readIReaderStore(INDEX_URL, source(), json).shouldBeNull()
    }

    @Test
    fun `an address other than the index file is not a store`() {
        readIReaderStore("$BASE/index.json", source(FREE_WEB_NOVEL), json).shouldBeNull()
    }

    @ParameterizedTest
    @CsvSource(
        "true, https://raw.githubusercontent.com/IReaderorg/IReader-extensions/repov2/index.min.json, true",
        "true, https://raw.githubusercontent.com/keiyoushi/extensions/repo/repo.json, false",
        "true, https://raw.githubusercontent.com/keiyoushi/extensions/repo, false",
        "false, https://raw.githubusercontent.com/IReaderorg/IReader-extensions/repov2/index.min.json, false",
    )
    fun `only a legacy row kept at its index file lists IReader extensions`(
        isLegacy: Boolean,
        indexUrl: String,
        expected: Boolean,
    ) {
        store.copy(indexUrl = indexUrl, isLegacy = isLegacy).isIReaderIndex shouldBe expected
    }

    private fun extensions(vararg entries: String) = readIReaderExtensions(store, source(*entries), json)

    private fun source(vararg entries: String) = Buffer().writeUtf8(entries.joinToString(",", "[", "]"))

    private companion object {
        const val BASE = "https://raw.githubusercontent.com/IReaderorg/IReader-extensions/repov2"
        const val INDEX_URL = "$BASE/index.min.json"
        const val OTHER_INDEX_URL = "https://raw.githubusercontent.com/someone/IReader-extensions/repov2/index.min.json"

        // The app's own configuration, which neither coerces nor reads leniently.
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        val store = ExtensionStore(
            indexUrl = INDEX_URL,
            name = "IReaderorg",
            badgeLabel = "IReaderorg",
            signingKey = "f4527fa6edd6de2a8ec987f9967bfdb8836dfef88e437a87ac81bba70557c17c",
            contact = ExtensionStore.Contact(
                website = "https://github.com/IReaderorg/IReader-extensions",
                discord = null,
            ),
            isLegacy = true,
            extensionListUrl = null,
        )

        // Entries as the live index carries them.
        const val FREE_WEB_NOVEL = """{"pkg":"ireader.freewebnovel.en","apk":"ireader-en-freewebnovel-v2.17.apk",""" +
            """"name":"FreeWebNovel","id":3970612267773145070,"lang":"en","code":17,"version":"2.17",""" +
            """"description":"","nsfw":false,"sourceDir":"freewebnovel"}"""
        const val NOVEL_FULL_NEW = """{"pkg":"ireader.novelfull.en","apk":"ireader-en-novelfull-v2.9.apk",""" +
            """"name":"NovelFull","id":1811616907726599068,"lang":"en","code":9,"version":"2.9",""" +
            """"description":"","nsfw":false,"sourceDir":"novelfull"}"""
        const val NOVEL_FULL_OLD = """{"pkg":"ireader.novelfull.en","apk":"ireader-en-novelfull-v2.1.apk",""" +
            """"name":"NovelFull","id":1811616907726599068,"lang":"en","code":1,"version":"2.1",""" +
            """"description":"","nsfw":false,"sourceDir":"novelfull"}"""

        // A tachiyomi index entry: an Int nsfw and no source id.
        const val TACHIYOMI_ENTRY =
            """{"name":"Tachiyomi: MangaDex","pkg":"eu.kanade.tachiyomi.extension.all.mangadex",""" +
                """"apk":"tachiyomi-all.mangadex-v1.4.190.apk","lang":"all","code":190,"version":"1.4.190","nsfw":0}"""
    }
}
