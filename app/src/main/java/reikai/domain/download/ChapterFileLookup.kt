package reikai.domain.download

import android.content.ContentResolver
import com.hippo.unifile.UniFile

/** Finds entries of this folder by name, for [lookups] chapters, by the rule [listsForLookups] states. */
fun UniFile.chapterFileLookup(lookups: Int): (String) -> UniFile? =
    if (listsForLookups(lookups)) filesByName()::get else ::findFile

/**
 * Whether [lookups] chapters are matched against one listing of this folder rather than asked by name. On a
 * storage access framework folder each `findFile` is a content resolver query, so from [LIST_MIN_LOOKUPS] on
 * it is listed once. A plain folder is always asked by name, since its listing can leave out files another
 * app created. Both download providers go by it, manga's through `DownloadProvider.findChapterDirs`.
 */
fun UniFile.listsForLookups(lookups: Int): Boolean =
    lookups >= LIST_MIN_LOOKUPS && uri.scheme == ContentResolver.SCHEME_CONTENT

/** This folder's entries by name, matched ignoring case, as `findFile` matches. */
fun UniFile.filesByName(): Map<String, UniFile> = listFiles().orEmpty()
    .filter { it.name != null }
    .associateByTo(sortedMapOf(String.CASE_INSENSITIVE_ORDER)) { it.name!! }

private const val LIST_MIN_LOOKUPS = 50
