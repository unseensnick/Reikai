package reikai.data.local

import reikai.domain.library.ContentType
import reikai.domain.local.LocalChapterMoveRepository
import reikai.domain.local.LocalVolumeOwnerRepository

/**
 * The names a local series of [type] may not take, beside what the local folder holds: every local series' address,
 * in the library or not, and every folder an owner row names, a missing group member's included, whose rows the
 * series would meet. Split out, Organize files and both relinks ask it.
 */
suspend fun localNamesHeld(
    type: ContentType,
    chapterMoves: LocalChapterMoveRepository,
    owners: LocalVolumeOwnerRepository,
): Set<String> = chapterMoves.localAddresses(type) + owners.all(type).flatMap { listOf(it.folder, it.owner) }

/** A novel name that is a loose book, a series of one; a manga folder is never one, whatever it is called. */
fun isLooseBook(type: ContentType, name: String): Boolean = type == ContentType.NOVELS && name.endsWith(EPUB, true)
