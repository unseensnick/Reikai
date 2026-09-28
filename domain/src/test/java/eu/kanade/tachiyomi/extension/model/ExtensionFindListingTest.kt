package eu.kanade.tachiyomi.extension.model

import io.kotest.matchers.shouldBe
import mihon.domain.extension.model.ContentWarning
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Test
import reikai.domain.extension.NO_SIGNING_KEY

/**
 * Which store an installed extension takes its updates from (mihon 093841105): only one whose key signs
 * it, and a keyless store only for an apk no added store's key signs.
 */
class ExtensionFindListingTest {

    @Test
    fun `a keyed store's listing needs the key the apk is signed with`() {
        installed(signedBy = "other").findListing(listOf(listing(keyed)), storeKeys = setOf(KEY)) shouldBe null
    }

    @Test
    fun `the newest listing among the stores that sign the apk is the one taken`() {
        val older = listing(keyed, versionCode = 2)
        val newer = listing(keyed.copy(indexUrl = "https://b.example/index.min.json"), versionCode = 3)

        installed(signedBy = KEY).findListing(listOf(older, newer), storeKeys = setOf(KEY)) shouldBe newer
    }

    @Test
    fun `a keyless store's listing is ignored for an apk an added store signs, whatever that store lists`() {
        installed(signedBy = KEY).findListing(listOf(listing(keyless)), storeKeys = setOf(KEY)) shouldBe null
    }

    @Test
    fun `a keyless store's listing is taken for an apk no added store signs`() {
        val offered = listing(keyless)

        installed(signedBy = "unknown").findListing(listOf(offered), storeKeys = setOf(KEY)) shouldBe offered
    }

    @Test
    fun `a listing no newer than the installed apk is no update`() {
        installed(signedBy = KEY).findUpdate(listOf(listing(keyed, versionCode = 1)), storeKeys = setOf(KEY)) shouldBe
            null
    }

    private fun installed(signedBy: String) = Extension.NotLoaded(
        name = "Ext",
        pkgName = PKG,
        versionName = "1",
        versionCode = 1,
        isShared = true,
        contentWarning = ContentWarning.SAFE,
        signatures = listOf(signedBy),
        kind = Extension.Kind.MANGA,
        libVersion = 1.5,
        reason = Extension.NotLoaded.Reason.Unsigned,
    )

    private fun listing(store: ExtensionStore, versionCode: Long = 2) = Extension.Available(
        name = "Ext",
        pkgName = PKG,
        versionName = "$versionCode",
        versionCode = versionCode,
        libVersion = 1.5,
        lang = "en",
        contentWarning = ContentWarning.SAFE,
        kind = Extension.Kind.MANGA,
        sources = emptyList(),
        apkUrl = "",
        iconUrl = "",
        store = store,
    )

    private companion object {
        const val PKG = "eu.kanade.tachiyomi.extension.en.example"
        const val KEY = "key"

        val keyed = ExtensionStore(
            indexUrl = "https://a.example/index.min.json",
            name = "Keyed",
            badgeLabel = "Keyed",
            signingKey = KEY,
            contact = ExtensionStore.Contact(website = "https://a.example", discord = null),
            isLegacy = false,
            extensionListUrl = null,
        )
        val keyless = keyed.copy(indexUrl = "https://c.example/index.min.json", signingKey = NO_SIGNING_KEY)
    }
}
