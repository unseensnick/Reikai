package exh.favorites

import eu.kanade.tachiyomi.source.online.all.EHentai
import exh.metadata.metadata.EHentaiSearchMetadata
import reikai.domain.track.RemoteFirstRemoval
import tachiyomi.domain.manga.model.Manga

/** Removes a gallery from the library; a failed removal from the account's favourites keeps it there. */
fun EHentai.removeGallery(
    removal: RemoteFirstRemoval,
    manga: Manga,
    alsoFromAccount: Boolean,
    removeFromLibrary: suspend () -> Unit,
) = removal.launch(
    name = name,
    alsoRemote = alsoFromAccount,
    remote = { removeFavorites(listOf(EHentaiSearchMetadata.galleryId(manga.url))) },
    local = removeFromLibrary,
)
