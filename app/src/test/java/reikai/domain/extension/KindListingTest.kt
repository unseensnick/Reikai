package reikai.domain.extension

import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.matchers.shouldBe
import mihon.domain.extension.model.ContentWarning
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * One rule for every apk kind, so each case runs for manga and both novel kinds. Two keyed stores, one
 * failed: only the apks the failed one can serve lose what the fetch says about them.
 */
class KindListingTest {

    @ParameterizedTest
    @EnumSource(Extension.Kind::class)
    fun `an apk whose store failed keeps its statuses while another store lists its kind`(kind: Extension.Kind) {
        installed(kind, signedBy = KEY_B).kindListing(listOf(listing(kind, STORE_A)), oneFailed, keyed, KEYS) shouldBe
            KindListing.Unknown
    }

    @ParameterizedTest
    @EnumSource(Extension.Kind::class)
    fun `an apk whose store failed keeps its statuses when no store lists its kind`(kind: Extension.Kind) {
        installed(kind, signedBy = KEY_B).kindListing(emptyList(), oneFailed, keyed, KEYS) shouldBe
            KindListing.Unknown
    }

    @ParameterizedTest
    @EnumSource(Extension.Kind::class)
    fun `an apk whose store answered derives from the listing while another store failed`(kind: Extension.Kind) {
        installed(kind, signedBy = KEY_A).kindListing(listOf(listing(kind, STORE_A)), oneFailed, keyed, KEYS) shouldBe
            KindListing.Listed
    }

    @ParameterizedTest
    @EnumSource(Extension.Kind::class)
    fun `an apk whose store answered and lists none of its kind is unlisted while another failed`(
        kind: Extension.Kind,
    ) {
        installed(kind, signedBy = KEY_A).kindListing(emptyList(), oneFailed, keyed, KEYS) shouldBe
            KindListing.Unlisted
    }

    @Test
    fun `a failed keyless store leaves an apk no added key signs unknown`() {
        val stores = listOf(STORE_A, KEYLESS)
        val statuses = mapOf(STORE_A.indexUrl to REACHED, KEYLESS.indexUrl to FAILED)

        installed(MANGA, signedBy = "own").kindListing(listOf(listing(MANGA, STORE_A)), statuses, stores, KEYS) shouldBe
            KindListing.Unknown
    }

    @Test
    fun `a failed keyless store says nothing about an apk an added store signs`() {
        val stores = listOf(STORE_A, KEYLESS)
        val statuses = mapOf(STORE_A.indexUrl to REACHED, KEYLESS.indexUrl to FAILED)

        installed(MANGA, signedBy = KEY_A).kindListing(listOf(listing(MANGA, STORE_A)), statuses, stores, KEYS) shouldBe
            KindListing.Listed
    }

    @Test
    fun `an apk of a store added since the fetch is unknown`() {
        val statuses = mapOf(STORE_A.indexUrl to REACHED)

        installed(MANGA, signedBy = KEY_B).kindListing(listOf(listing(MANGA, STORE_A)), statuses, keyed, KEYS) shouldBe
            KindListing.Unknown
    }

    @Test
    fun `before the first fetch nothing is known`() {
        installed(MANGA, signedBy = "own").kindListing(emptyList(), null, emptyList(), emptySet()) shouldBe
            KindListing.Unknown
    }

    @Test
    fun `with no store added nothing can update an apk`() {
        installed(MANGA, signedBy = "own").kindListing(emptyList(), emptyMap(), emptyList(), emptySet()) shouldBe
            KindListing.Unlisted
    }

    private val keyed = listOf(STORE_A, STORE_B)
    private val oneFailed = mapOf(STORE_A.indexUrl to REACHED, STORE_B.indexUrl to FAILED)

    private fun installed(kind: Extension.Kind, signedBy: String) = Extension.NotLoaded(
        name = "Installed",
        pkgName = "pkg.installed",
        versionName = "1",
        versionCode = 1,
        isShared = false,
        contentWarning = ContentWarning.SAFE,
        signatures = listOf(signedBy),
        kind = kind,
        hasUpdate = true,
        reason = Extension.NotLoaded.Reason.Filtered,
    )

    private fun listing(kind: Extension.Kind, store: ExtensionStore) = Extension.Available(
        name = "Entry",
        pkgName = "pkg.${kind.name}",
        versionName = "1.6.1",
        versionCode = 1,
        libVersion = 1.6,
        lang = "en",
        contentWarning = ContentWarning.SAFE,
        kind = kind,
        sources = emptyList(),
        apkUrl = "a.apk",
        iconUrl = "a.png",
        store = store,
    )

    private companion object {
        val MANGA = Extension.Kind.MANGA
        const val KEY_A = "keyA"
        const val KEY_B = "keyB"
        val KEYS = setOf(KEY_A, KEY_B)
        val STORE_A = store("a", KEY_A)
        val STORE_B = store("b", KEY_B)
        val KEYLESS = store("k", NO_SIGNING_KEY)
        val REACHED = RepoStatus.Reached(manga = 1, novels = 1)
        val FAILED = RepoStatus.Unreachable("HTTP 503")

        fun store(host: String, signingKey: String) = ExtensionStore(
            indexUrl = "https://$host.example/index.pb",
            name = host,
            badgeLabel = host.uppercase(),
            signingKey = signingKey,
            contact = ExtensionStore.Contact(website = "https://$host.example", discord = null),
            isLegacy = false,
            extensionListUrl = null,
        )
    }
}
