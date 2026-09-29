package reikai.domain.merge

/**
 * One member chapter of a merge group, carrying what a download probe needs to find its file: the
 * folder name is built from the chapter's own name, scanlator and url under its own entry. Novels
 * have no scanlator and pass null.
 */
data class DownloadUnitRow(
    val groupId: Long,
    val unit: Int,
    val ownerId: Long,
    val chapterName: String,
    val scanlator: String?,
    val chapterUrl: String,
)

/**
 * One copy of the merged chapter [namedId] names, [copy] placing it in the stitch, with what a download
 * probe needs: the owner's stored title and source, and the chapter's own name, scanlator and url.
 * [ownerSource] is the source id as text, since a manga source is a number and a novel source is not.
 */
data class ChapterCopyRow(
    val namedId: Long,
    val copy: ChapterUnit,
    val ownerTitle: String,
    val ownerSource: String,
    val chapterName: String,
    val scanlator: String?,
    val chapterUrl: String,
)

/**
 * How many of each group's merged chapters have a copy on disk, so a merged row's download badge
 * counts a chapter two sources both hold once, the way its unread badge already counts it once.
 *
 * [ownersWithDownloads] is what keeps this cheap: an entry that has downloaded nothing cannot hold a
 * copy, and most members of most groups have not, so their rows are never probed. Without it this
 * walks every chapter of every merged group on each library emission.
 */
fun downloadedUnitsByGroup(
    rowsByGroup: Map<Long, List<DownloadUnitRow>>,
    ownersWithDownloads: Set<Long>,
    isDownloaded: (DownloadUnitRow) -> Boolean,
): Map<Long, Int> {
    if (ownersWithDownloads.isEmpty()) return emptyMap()
    return rowsByGroup.mapValues { (_, rows) ->
        rows.asSequence()
            .filter { it.ownerId in ownersWithDownloads && isDownloaded(it) }
            .mapTo(HashSet()) { it.unit }
            .size
    }
}
