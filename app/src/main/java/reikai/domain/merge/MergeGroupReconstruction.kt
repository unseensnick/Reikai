package reikai.domain.merge

import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import tachiyomi.core.common.preference.Preference

/**
 * Pure reconstruction of the 0.3.x pref-based grouping into a clean partition, for an install upgrading
 * past it ([mihon.core.migration.migrations.MigrateMergePrefsToGroupsMigration]) and for a restored
 * backup made then ([RestoreMergeGroups.fromBackup]). Connected components over the manual merges
 * (which always group, overriding unmerges) plus, when auto-merge-by-title is on, the same-title
 * candidates (novels also matching author under the author guard), with explicit unmerge pairs
 * excluded. Each entry lands in exactly one group.
 */
object MergeGroupReconstruction {

    data class Candidate(val id: Long, val title: String, val author: String?)

    data class TitleSwitches(val autoMergeByTitle: Boolean, val requireAuthor: Boolean)

    /**
     * [contentType]'s same-title switches, each read through [read]: the live value on an upgrade, the
     * backup's own on a restore. Manga never had the author guard.
     */
    fun titleSwitches(
        contentType: ContentType,
        prefs: ReikaiLibraryPreferences,
        read: (Preference<Boolean>) -> Boolean,
    ): TitleSwitches = when (contentType) {
        ContentType.MANGA -> TitleSwitches(read(prefs.autoMergeSameTitle), requireAuthor = false)
        ContentType.NOVELS ->
            TitleSwitches(read(prefs.novelAutoMergeSameTitle), read(prefs.novelAutoMergeRequireAuthor))
        ContentType.ALL -> error("A merge group belongs to one content type")
    }

    /**
     * A retired merge pref's comma-joined id groups as ids. [survivors] maps an id the upgrade's dedupe
     * merged away to the entry it merged into, since the prefs still name the old id.
     */
    fun parsePrefGroups(entries: Set<String>, survivors: Map<Long, Long>): List<List<Long>> =
        entries.map { entry ->
            entry.split(",").mapNotNull { raw -> raw.trim().toLongOrNull()?.let { survivors[it] ?: it } }
        }

    /**
     * Disjoint groups of 2+ ids, each sorted ascending; single entries are dropped. An unmerge is a pair,
     * so any other size is ignored.
     */
    fun reconstruct(
        candidates: List<Candidate>,
        manualMerges: List<List<Long>>,
        unmerges: List<List<Long>>,
        switches: TitleSwitches,
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
        for (group in manualMerges) {
            val members = group.filter { it in present }
            for (i in 1 until members.size) union(members[0], members[i])
        }

        // Same-title auto-grouping, honoring the author guard and the unmerge exclusions.
        if (switches.autoMergeByTitle) {
            val unmergedPairs = unmerges.mapNotNullTo(HashSet()) { pair ->
                pair.takeIf { it.size == 2 }?.let { (a, b) -> if (a < b) a to b else b to a }
            }
            val buckets = HashMap<String, MutableList<Long>>()
            for (candidate in candidates) {
                val key = autoKey(candidate, switches.requireAuthor) ?: continue
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
