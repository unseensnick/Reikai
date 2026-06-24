package exh.eh

import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 2b stub. The E-Hentai source only needs an in-session cache of each gallery's parent
 * (older version) so chapter loading doesn't re-walk the version chain over the network every
 * time. The full helper (disk-backed lookup table plus gallery-version reconciliation across
 * favorites, history and categories) lands with the favorites-sync phase; the [parentLookupTable]
 * surface is kept identical so that swap is mechanical.
 */
class EHentaiUpdateHelper {
    val parentLookupTable = ParentLookupTable()

    class ParentLookupTable {
        private val map = ConcurrentHashMap<Int, GalleryEntry>()

        fun get(key: Int): GalleryEntry? = map[key]

        fun put(key: Int, value: GalleryEntry) {
            map[key] = value
        }
    }
}

data class GalleryEntry(val gId: String, val gToken: String)
