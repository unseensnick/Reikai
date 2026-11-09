package reikai.domain.merge

/**
 * What names one member of a merge on its chip, its Manage sources row and its chapters: the source's
 * name, or for a local series its own title, since every local series shares one source name.
 */
fun mergeMemberName(sourceName: String, title: String, isLocal: Boolean): String =
    if (isLocal && title.isNotBlank()) title else sourceName
