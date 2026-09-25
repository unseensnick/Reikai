package reikai.domain.source

/** What a refresh does to an entry's cover: drop the cached file, and stamp it so every screen reloads it. */
enum class CoverRefresh(val deletesCachedFile: Boolean, val stamps: Boolean) {
    KEEP(deletesCachedFile = false, stamps = false),
    STAMP(deletesCachedFile = false, stamps = true),
    DELETE(deletesCachedFile = true, stamps = false),
    DELETE_AND_STAMP(deletesCachedFile = true, stamps = true),
}

/**
 * Mihon's cover rule from `UpdateMangaFromRemote`, kept here so manga and novels follow one. A cover whose
 * address did not change is left alone unless the refresh was asked for by hand ([manualFetch]), which is
 * how a single series repairs a broken cover. [refreshedUrl] is the source's cover after [keptCover].
 */
fun refreshedCover(
    storedUrl: String?,
    refreshedUrl: String?,
    manualFetch: Boolean,
    isLocal: Boolean,
    hasCustomCover: () -> Boolean,
): CoverRefresh = when {
    // Never refresh covers if the url is empty to avoid "losing" existing covers
    refreshedUrl == null -> CoverRefresh.KEEP
    !manualFetch && storedUrl == refreshedUrl -> CoverRefresh.KEEP
    isLocal -> CoverRefresh.STAMP
    // The custom cover is what shows, so there is nothing to reload.
    hasCustomCover() -> CoverRefresh.DELETE
    else -> CoverRefresh.DELETE_AND_STAMP
}
