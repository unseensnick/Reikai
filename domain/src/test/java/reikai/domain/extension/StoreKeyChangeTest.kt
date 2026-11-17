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

    /** The names come from a store's index, so any character must survive the round trip. */
    @Test
    fun `a refusal whose store name holds a line break survives being saved and read back`() {
        val named = refusal.copy(name = "My\nStore")

        RefusedStore.decode(named.encode()) shouldBe named
    }

    @Test
    fun `a refresh that reached a refused store with its own key back lifts the refusal`() {
        StoreRefresh(refused = emptyList(), reached = setOf(URL)).refusalsAfter(listOf(refusal), setOf(URL)) shouldBe
            emptyList()
    }

    /** A legacy or list-url store's listing is refused only by the saved refusal, so a failed fetch keeps it. */
    @Test
    fun `a refresh that could not reach a refused store keeps the refusal`() {
        StoreRefresh(refused = emptyList(), reached = emptySet()).refusalsAfter(listOf(refusal), setOf(URL)) shouldBe
            listOf(refusal)
    }

    @Test
    fun `a refusal of a store no longer saved is dropped`() {
        StoreRefresh(refused = emptyList(), reached = emptySet()).refusalsAfter(listOf(refusal), emptySet()) shouldBe
            emptyList()
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
