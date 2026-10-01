package reikai.domain.extension

import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.matchers.shouldBe
import mihon.domain.extension.model.ContentWarning
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** One rule for every apk kind, so each case runs for manga and both novel kinds. */
class KindListingTest {

    @ParameterizedTest
    @EnumSource(Extension.Kind::class)
    fun `a kind no store lists after a failed store keeps its statuses`(kind: Extension.Kind) {
        val otherKind = Extension.Kind.entries.first { it != kind }
        val fetched = listOf(available(otherKind))

        kindListing(fetched.filter { it.kind == kind }, statuses(answered = 1, failed = 1)) shouldBe
            KindListing.Unknown
    }

    @ParameterizedTest
    @EnumSource(Extension.Kind::class)
    fun `a kind no store lists once every store answered is unlisted`(kind: Extension.Kind) {
        val otherKind = Extension.Kind.entries.first { it != kind }
        val fetched = listOf(available(otherKind))

        kindListing(fetched.filter { it.kind == kind }, statuses(answered = 2, failed = 0)) shouldBe
            KindListing.Unlisted
    }

    @ParameterizedTest
    @EnumSource(Extension.Kind::class)
    fun `a listed kind derives from its listing even after a failed store`(kind: Extension.Kind) {
        val listing = listOf(available(kind))

        kindListing(listing, statuses(answered = 1, failed = 1)) shouldBe KindListing.Listed(listing)
    }

    @Test
    fun `before the first fetch nothing is known`() {
        kindListing(emptyList(), statuses = null) shouldBe KindListing.Unknown
    }

    @Test
    fun `with no store added nothing can update an apk`() {
        kindListing(emptyList(), statuses = emptyMap()) shouldBe KindListing.Unlisted
    }

    private fun statuses(answered: Int, failed: Int): Map<String, RepoStatus> =
        List(answered) { "answered$it" to RepoStatus.Reached(manga = 1, novels = 0) }.toMap() +
            List(failed) { "failed$it" to RepoStatus.Unreachable("HTTP 503") }.toMap()

    private fun available(kind: Extension.Kind) = Extension.Available(
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
        store = ExtensionStore(
            indexUrl = "https://example.org/index.pb",
            name = "Store",
            badgeLabel = "ST",
            signingKey = "key",
            contact = ExtensionStore.Contact(website = "https://example.org", discord = null),
            isLegacy = false,
            extensionListUrl = null,
        ),
    )
}
