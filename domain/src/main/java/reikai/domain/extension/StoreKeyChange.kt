package reikai.domain.extension

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
data class RefusedStore(val indexUrl: String, val name: String, val servedKey: String, val servedName: String) {
    fun encode(): String = listOf(indexUrl, name, servedKey, servedName).joinToString(SEPARATOR)

    /** Whether it still holds for [store], the one saved at [indexUrl]: adding it again takes [servedKey]. */
    fun appliesTo(store: ExtensionStore): Boolean = store.indexUrl == indexUrl && store.servesAnotherStore(servedKey)

    companion object {
        private const val SEPARATOR = "\n"

        fun decode(value: String): RefusedStore? = value.split(SEPARATOR, limit = 4)
            .takeIf { it.size == 4 }
            ?.let { (indexUrl, name, servedKey, servedName) -> RefusedStore(indexUrl, name, servedKey, servedName) }
    }
}

/** Why a refused store lists nothing: its address serves [servedName], signed by another key. */
class StoreKeyChangedException(val servedName: String) :
    IllegalStateException("The address now serves $servedName, signed by another key")
