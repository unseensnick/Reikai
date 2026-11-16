package reikai.domain.extension

import io.kotest.matchers.shouldBe
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Test

class StoreKeyChangeTest {

    private val refusal = RefusedStore(URL, "Store", servedKey = "other", servedName = "Other")

    @Test
    fun `a store saved without a key takes the key it is served`() {
        store(NO_SIGNING_KEY).servesAnotherStore("other") shouldBe false
    }

    @Test
    fun `a refusal survives being saved and read back`() {
        RefusedStore.decode(refusal.encode()) shouldBe refusal
    }

    @Test
    fun `a refusal holds while the store keeps its old key`() {
        refusal.appliesTo(store("key")) shouldBe true
    }

    @Test
    fun `a store added again under the new key is no longer refused`() {
        refusal.appliesTo(store("other")) shouldBe false
    }

    private fun store(signingKey: String) = ExtensionStore(
        indexUrl = URL,
        name = "Store",
        badgeLabel = "",
        signingKey = signingKey,
        contact = ExtensionStore.Contact(website = "", discord = null),
        isLegacy = false,
        extensionListUrl = null,
    )

    private companion object {
        const val URL = "https://example.org/index.min.json"
    }
}
