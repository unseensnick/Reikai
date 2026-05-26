package eu.kanade.tachiyomi.data.database.models

import eu.kanade.tachiyomi.ui.library.LibrarySort
import java.io.Serializable

/**
 * Common surface across [Category] (manga) and [NovelCategory] (novel) that the shared Compose
 * library composable reads when rendering headers, hopper labels, dynamic-collapse keys, and
 * per-category sort labels. Defined as the smallest subset both interfaces already expose so
 * adding the inheritance declaration is a no-op on the implementing types.
 *
 * Divergent fields ([Category.sourceId] / [NovelCategory.sourceId] type differs;
 * `mangaSort` vs `novelSort` field name and persistence) stay off the common surface; the
 * shared composable reads them through [sortingMode] / [isAscending] / [dynamicHeaderKey]
 * which both sides implement against their own backing field.
 */
interface ILibraryCategory : Serializable {

    var id: Int?

    var name: String

    var order: Int

    var isHidden: Boolean

    var isDynamic: Boolean

    fun isAscending(): Boolean

    fun sortingMode(): LibrarySort?

    fun dynamicHeaderKey(): String
}
