package reikai.util

/** Union-find over arbitrary keys. A key never passed to [union] is a set of its own. */
class DisjointSet<T> {

    // Only non-root keys have an entry, so an unseen key needs no registration.
    private val parent = HashMap<T, T>()

    fun find(x: T): T {
        var root = x
        while (true) root = parent[root] ?: break
        var node = x
        while (node != root) {
            val next = parent.getValue(node)
            parent[node] = root
            node = next
        }
        return root
    }

    fun union(a: T, b: T) {
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) parent[rb] = ra
    }
}
