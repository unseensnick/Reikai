package reikai.presentation.migrate.flow

/** Whether [url] on [sourceKey] is this entry's own listing, which is never a migration target. */
fun MigrationEntry.isOwnListing(sourceKey: String, url: String): Boolean {
    val ownUrl = when (payload) {
        is MigrationPayload.OfManga -> payload.manga.url
        is MigrationPayload.OfNovel -> payload.novel.url
    }
    return sourceKey == this.sourceKey && url == ownUrl
}
