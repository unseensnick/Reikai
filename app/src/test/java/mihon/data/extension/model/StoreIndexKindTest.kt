package mihon.data.extension.model

import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.matchers.shouldBe
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Test

@OptIn(ExperimentalSerializationApi::class)
class StoreIndexKindTest {

    private val store = ExtensionStore(
        indexUrl = "https://example.org/index.pb",
        name = "Store",
        badgeLabel = "S",
        signingKey = "key",
        contact = ExtensionStore.Contact(website = "https://example.org", discord = null),
        isLegacy = false,
        extensionListUrl = null,
    )

    private fun entry(pkgName: String, isNovel: Boolean) = NetworkExtensionStore.Extension(
        name = "Entry",
        packageName = pkgName,
        resources = NetworkExtensionStore.Resources(apkUrl = "a.apk", iconUrl = "a.png"),
        extensionLib = "1.6",
        versionCode = 1,
        versionName = "1.6.1",
        contentWarning = NetworkExtensionStore.ContentWarning.SAFE,
        sources = listOf(NetworkExtensionStore.Source(id = 1, name = "Entry", language = "en")),
        isNovel = isNovel,
    )

    private fun kindOf(entry: NetworkExtensionStore.Extension) =
        NetworkExtensionStore.ExtensionList(listOf(entry)).toAvailableExtensions(store).single().kind

    @Test
    fun `a NovelSourcery index entry decodes its novel field`() {
        // BoxNovel's entry, cut from NovelSourcery's index.pb and wrapped as a one-entry list
        val bytes = BOX_NOVEL_ENTRY.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        ProtoBuf.decodeFromByteArray<NetworkExtensionStore.ExtensionList>(bytes).extensions.single().isNovel shouldBe
            true
    }

    @Test
    fun `an entry marked novel is a novel extension whatever its package`() {
        kindOf(entry("eu.kanade.tachiyomi.extension.en.example", isNovel = true)) shouldBe
            Extension.Kind.TACHIYOMI_NOVEL
    }

    @Test
    fun `an unmarked entry in the novel package namespace is a novel extension`() {
        kindOf(entry("eu.kanade.tachiyomi.novelextension.en.example", isNovel = false)) shouldBe
            Extension.Kind.TACHIYOMI_NOVEL
    }

    @Test
    fun `an unmarked manga entry stays manga`() {
        kindOf(entry("eu.kanade.tachiyomi.extension.en.example", isNovel = false)) shouldBe Extension.Kind.MANGA
    }

    @Test
    fun `an old-format index entry in the novel package namespace is a novel extension`() {
        val legacy = NetworkLegacyExtension(
            name = "Tachiyomi: Entry",
            pkg = "eu.kanade.tachiyomi.novelextension.en.example",
            apk = "a.apk",
            lang = "en",
            code = 1,
            version = "1.6.1",
            nsfw = 0,
            sources = null,
        )

        legacy.toAvailableExtension(store, "https://example.org").kind shouldBe Extension.Kind.TACHIYOMI_NOVEL
    }

    private companion object {
        const val BOX_NOVEL_ENTRY =
            "0abf030a08426f784e6f76656c122e65752e6b616e6164652e7461636869796f6d692e6e6f76656c657874656e73696f" +
                "6e2e656e2e626f786e6f76656c1abc020a5f68747470733a2f2f63646e2e6a7364656c6976722e6e65742f67682f6e" +
                "6f76656c736f7572636572792f657874656e73696f6e73407265706f2f61706b2f7461636869796f6d692d656e2e" +
                "626f786e6f76656c2d76312e362e31312e61706b127168747470733a2f2f63646e2e6a7364656c6976722e6e6574" +
                "2f67682f6e6f76656c736f7572636572792f657874656e73696f6e732d736f75726365406d61696e2f7372632f65" +
                "6e2f626f786e6f76656c2f7265732f6d69706d61702d78686470692f69635f6c61756e636865722e706e67aa1f65" +
                "68747470733a2f2f7261772e67697468756275736572636f6e74656e742e636f6d2f6e6f76656c736f7572636572" +
                "792f657874656e73696f6e732f7265706f2f6a61722f7461636869796f6d692d656e2e626f786e6f76656c2d7631" +
                "2e362e31312e6a61722203312e36280b3206312e362e31313801422f08ec948099eedaaaa06e1208426f784e6f76" +
                "656c1a02656e221568747470733a2f2f6e6f76656c6e6963652e636f6d80f40301"
    }
}
