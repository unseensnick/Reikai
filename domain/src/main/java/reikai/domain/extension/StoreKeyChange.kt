package reikai.domain.extension

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mihon.domain.extension.model.ExtensionStore

/**
 * Whether an index signed by [servedKey] is another store now served at this one's address. A saved
 * store with no key yet (one carried over from a preference) takes whatever key it is served.
 */
fun ExtensionStore.servesAnotherStore(servedKey: String): Boolean = hasSigningKey && servedKey != signingKey

/**
 * A saved store, named [name], whose address a refresh found serving [servedName] signed by [servedKey].
 * Kept in a preference, so its listing stays refused across restarts until a refresh finds the store's own
 * key again, or the store is added again under the new key.
 */
@Serializable
data class RefusedStore(val indexUrl: String, val name: String, val servedKey: String, val servedName: String) {
    fun encode(): String = Json.encodeToString(this)

    /** Whether it still holds for [store], the one saved at [indexUrl]: adding it again takes [servedKey]. */
    fun appliesTo(store: ExtensionStore): Boolean = store.indexUrl == indexUrl && store.servesAnotherStore(servedKey)

    companion object {
        fun decode(
            value: String,
        ): RefusedStore? = runCatching { Json.decodeFromString<RefusedStore>(value) }.getOrNull()
    }
}

/** What a refresh of the saved stores found: the stores it [refused], and every store it [reached], those included. */
data class StoreRefresh(val refused: List<RefusedStore>, val reached: Set<String>) {
    /**
     * The refusals to keep after it, given the ones [told] before and the stores still [saved]: its own, plus
     * any earlier one whose store it could not reach, since a list-url or legacy store's listing is refused by
     * the saved refusal alone.
     */
    fun refusalsAfter(told: Collection<RefusedStore>, saved: Set<String>): List<RefusedStore> =
        refused + told.filter { it.indexUrl !in reached && it.indexUrl in saved }
}

/** Why a refused store lists nothing: its address serves [servedName], signed by another key. */
class StoreKeyChangedException(val servedName: String) :
    IllegalStateException("The address now serves $servedName, signed by another key")
