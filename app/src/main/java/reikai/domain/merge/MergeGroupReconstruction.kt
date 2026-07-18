package reikai.domain.merge

/**
 * Pure reconstruction of today's pref-based grouping into a clean partition, for the Phase 1 one-time
 * migration ([mihon.core.migration.migrations.MigrateMergePrefsToGroupsMigration]).
 *
 * Builds connected components over the manual-merge entries (which always group, overriding unmerges)
 * plus, when auto-merge-by-title is on, the same-title candidates (novels also matching author when the
 * author guard is on), with explicit unmerge pairs excluded so a pair the user deliberately split is not
 * re-grouped. Each entry lands in exactly one group, matching the new schema's one-group-per-entry rule.
 *
 * Where today's two grouping definitions (library collapse vs details/reader) disagree, this levels up:
 * transitively connected same-title members end up in one group rather than half-grouped, so nothing that
 * was grouped becomes ungrouped.
 */
object MergeGroupReconstruction {

    data class Candidate(val id: Long, val title: String, val author: String?)

    /** Disjoint groups of 2+ ids, each sorted ascending; single entries are dropped. */
    fun reconstruct(
        candidates: List<Candidate>,
        manualMerges: Set<String>,
        unmerges: Set<String>,
        autoMergeByTitle: Boolean,
        requireAuthor: Boolean,
    ): List<List<Long>> {
        if (candidates.isEmpty()) return emptyList()

        val present = candidates.mapTo(HashSet()) { it.id }
        val parent = HashMap<Long, Long>(present.size).apply { present.forEach { put(it, it) } }

        fun find(x: Long): Long {
            var root = x
            while (parent[root] != root) root = parent.getValue(root)
            var node = x
            while (parent[node] != node) {
                val next = parent.getValue(node)
                parent[node] = root
                node = next
            }
            return root
        }
        fun union(a: Long, b: Long) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        // Manual merges always group; they override unmerges by construction.
        for (entry in manualMerges) {
            val members = entry.split(",").mapNotNull { it.trim().toLongOrNull() }.filter { it in present }
            for (i in 1 until members.size) union(members[0], members[i])
        }

        // Same-title auto-grouping, honoring the author guard and the unmerge exclusions.
        if (autoMergeByTitle) {
            val unmergedPairs = parseUnmergedPairs(unmerges)
            val buckets = HashMap<String, MutableList<Long>>()
            for (candidate in candidates) {
                val key = autoKey(candidate, requireAuthor) ?: continue
                buckets.getOrPut(key) { mutableListOf() }.add(candidate.id)
            }
            for (bucket in buckets.values) {
                for (i in bucket.indices) {
                    for (j in i + 1 until bucket.size) {
                        val a = bucket[i]
                        val b = bucket[j]
                        val pair = if (a < b) a to b else b to a
                        if (pair !in unmergedPairs) union(a, b)
                    }
                }
            }
        }

        return candidates.asSequence()
            .map { it.id }
            .groupBy(::find)
            .values
            .filter { it.size >= 2 }
            .map { it.sorted() }
            .sortedBy { it.first() }
    }

    // Normalized "min,max" unmerge pairs parsed from the pref set; malformed entries are dropped.
    private fun parseUnmergedPairs(unmerges: Set<String>): Set<Pair<Long, Long>> {
        if (unmerges.isEmpty()) return emptySet()
        return unmerges.mapNotNullTo(HashSet()) { entry ->
            val parts = entry.split(",")
            if (parts.size != 2) return@mapNotNullTo null
            val a = parts[0].trim().toLongOrNull() ?: return@mapNotNullTo null
            val b = parts[1].trim().toLongOrNull() ?: return@mapNotNullTo null
            if (a < b) a to b else b to a
        }
    }

    // Mirrors the live same-title key: title alone, or title + author when the guard is on and the
    // author is non-blank; a blank title (or guarded blank author) never auto-groups.
    private fun autoKey(candidate: Candidate, requireAuthor: Boolean): String? {
        val title = candidate.title.lowercase().trim()
        if (title.isEmpty()) return null
        if (!requireAuthor) return "t:$title"
        val author = candidate.author?.lowercase()?.trim().orEmpty()
        if (author.isEmpty()) return null
        return "t:$title|a:$author"
    }
}
