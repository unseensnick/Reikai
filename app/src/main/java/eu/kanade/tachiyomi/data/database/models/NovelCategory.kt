package eu.kanade.tachiyomi.data.database.models

import android.content.Context
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.ui.library.LibrarySort
import java.io.Serializable
import yokai.i18n.MR
import yokai.util.lang.getString

interface NovelCategory : Serializable {

    var id: Int?

    var name: String

    var order: Int

    var flags: Int

    var novelOrder: List<Long>

    var novelSort: Char?

    var isAlone: Boolean

    var isHidden: Boolean

    var isDynamic: Boolean

    var sourceId: Long?

    var langId: String?

    var isSystem: Boolean

    fun isAscending(): Boolean {
        return ((novelSort?.minus('a') ?: 0) % 2) != 1
    }

    fun sortingMode(): LibrarySort? = LibrarySort.valueOf(novelSort)

    val isDragAndDrop
        get() = (
            novelSort == null ||
                novelSort == LibrarySort.DragAndDrop.categoryValue
            ) && !isDynamic

    fun sortRes(): StringResource =
        (LibrarySort.valueOf(novelSort) ?: LibrarySort.DragAndDrop).stringRes(isDynamic)

    fun changeSortTo(sort: Int) {
        novelSort = (LibrarySort.valueOf(sort) ?: LibrarySort.Title).categoryValue
    }

    fun novelOrderToString(): String =
        if (novelSort != null) novelSort.toString() else novelOrder.joinToString("/")

    fun dynamicHeaderKey(): String {
        if (!isDynamic) throw IllegalStateException("This category is not a dynamic category")

        return when {
            sourceId != null -> "${name}$sourceSplitter${sourceId}"
            langId != null -> "${langId}$langSplitter${name}"
            else -> name
        }
    }

    companion object {
        const val sourceSplitter = "◘•◘"
        const val langSplitter = "⨼⨦⨠"

        var lastCategoriesAddedTo = emptySet<Int>()

        fun create(name: String): NovelCategory = NovelCategoryImpl().apply {
            this.name = name
        }

        fun createDefault(context: Context): NovelCategory =
            create(context.getString(MR.strings.default_value)).apply {
                id = 0
                isSystem = true
            }

        fun createCustom(name: String, libSort: Int, ascending: Boolean): NovelCategory =
            create(name).apply {
                val librarySort = LibrarySort.valueOf(libSort) ?: LibrarySort.DragAndDrop
                changeSortTo(librarySort.mainValue)
                if (novelSort != LibrarySort.DragAndDrop.categoryValue && !ascending) {
                    novelSort = novelSort?.plus(1)
                }
                isDynamic = true
            }

        fun createAll(context: Context, libSort: Int, ascending: Boolean): NovelCategory =
            createCustom(context.getString(MR.strings.all), libSort, ascending).apply {
                id = -1
                order = -1
                isAlone = true
                isSystem = true
            }

        fun novelOrderFromString(orderString: String?): Pair<Char?, List<Long>> {
            return when {
                orderString.isNullOrBlank() -> {
                    Pair('a', emptyList())
                }
                orderString.firstOrNull()?.isLetter() == true -> {
                    Pair(orderString.first(), emptyList())
                }
                else -> Pair(null, orderString.split("/").mapNotNull { it.toLongOrNull() })
            }
        }

        fun mapper(
            id: Long,
            name: String,
            sort: Long,
            flags: Long,
            orderString: String,
        ) = create(name).also {
            it.id = id.toInt()
            it.name = name
            it.order = sort.toInt()
            it.flags = flags.toInt()

            val (novelSort, order) = novelOrderFromString(orderString)
            if (novelSort != null) it.novelSort = novelSort
            it.novelOrder = order
        }
    }
}
