package reikai.domain.source

/**
 * The title a refresh stores for an entry, or null to keep the stored one. Mihon's rule from
 * `UpdateMangaFromRemote`, kept here so manga and novels follow one: the source's title, unless the
 * entry is in the library and "Update library titles to match source" is off.
 */
fun refreshedTitle(remote: String?, isFavorite: Boolean, updateTitles: Boolean): String? =
    remote?.takeIf { it.isNotBlank() && (!isFavorite || updateTitles) }
