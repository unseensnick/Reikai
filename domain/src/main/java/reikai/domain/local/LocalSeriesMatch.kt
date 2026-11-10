package reikai.domain.local

/**
 * The folders a local series whose own folder is gone may have been renamed to: each of [folders] (name to the
 * paths it holds, relative to it, a volume folder as `volume/` and its files as `volume/file`) that holds every
 * one of the series' [chapterPaths], compared case-blind as the disk finds them. A series with no chapter paths
 * matches nothing, since nothing then tells one folder from another. The answer is evidence for the user to
 * confirm, never an identity: two series can share file names.
 */
fun relinkCandidates(chapterPaths: Collection<String>, folders: Map<String, Collection<String>>): List<String> {
    if (chapterPaths.isEmpty()) return emptyList()
    val required = chapterPaths.mapTo(HashSet()) { it.lowercase() }
    return folders.entries
        .filter { (_, paths) -> paths.mapTo(HashSet()) { it.lowercase() }.containsAll(required) }
        .map { it.key }
        .sortedBy { it.lowercase() }
}
