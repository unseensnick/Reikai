package reikai.domain.download

import android.content.ContentResolver
import com.hippo.unifile.UniFile

/**
 * Finds entries of this folder by name, for [lookups] chapters. On a storage access framework folder each
 * `findFile` is a content resolver query, so from [LIST_MIN_LOOKUPS] on the folder is listed once and matched
 * ignoring case, as `findFile` matches. A plain folder is always asked by name, since its listing can leave out
 * files another app created. The rule of Mihon's private `DownloadProvider.findChapterDirs`, which manga keeps.
 */
fun UniFile.chapterFileLookup(lookups: Int): (String) -> UniFile? {
    if (lookups < LIST_MIN_LOOKUPS || uri.scheme != ContentResolver.SCHEME_CONTENT) return ::findFile
    val byName = listFiles().orEmpty()
        .filter { it.name != null }
        .associateByTo(sortedMapOf(String.CASE_INSENSITIVE_ORDER)) { it.name!! }
    return byName::get
}

private const val LIST_MIN_LOOKUPS = 50
