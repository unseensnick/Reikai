package reikai.domain.extension

import io.kotest.matchers.shouldBe
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Test

class StoreSigningKeysTest {

    @Test
    fun `a keyless store adds no key an apk could be signed with`() {
        listOf(store(KEY), store(NO_SIGNING_KEY)).signingKeys shouldBe setOf(KEY)
    }

    private fun store(signingKey: String) = ExtensionStore(
        indexUrl = "https://$signingKey.example/index.min.json",
        name = "Store",
        badgeLabel = "",
        signingKey = signingKey,
        contact = ExtensionStore.Contact(website = "", discord = null),
        isLegacy = false,
        extensionListUrl = null,
    )

    private companion object {
        const val KEY = "abc"
    }
}
