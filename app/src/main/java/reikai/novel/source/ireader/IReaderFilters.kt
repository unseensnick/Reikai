package reikai.novel.source.ireader

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import ireader.core.source.model.Filter as IReaderFilter

/*
 * IReader's filters shown in Mihon's filter sheet and handed back to the source. Each Mihon filter
 * wraps the IReader one it came from and writes its value back when a search runs. The query is not
 * one of them: IReader carries it as a Title filter, so the sheet leaves Title out and the search box
 * fills it.
 */

/** A Mihon filter standing in for an IReader one, which it updates before a search. */
internal interface IReaderBacked {
    val original: IReaderFilter<*>

    fun writeBack()
}

/** The Mihon filters for [filters], for the sheet. */
internal fun toMihonFilters(filters: List<IReaderFilter<*>>): FilterList = FilterList(filters.mapNotNull(::toMihon))

/**
 * The IReader filters a search hands the source: the ones [mihon] stands in for, with the values the
 * reader picked, and the query as a Title.
 */
internal fun toIReaderFilters(mihon: FilterList, query: String): List<IReaderFilter<*>> =
    mihon.filterIsInstance<IReaderBacked>().onEach { it.writeBack() }.map { it.original } +
        IReaderFilter.Title().apply { value = query }

private fun toMihon(filter: IReaderFilter<*>): Filter<*>? = when (filter) {
    is IReaderFilter.Title -> null
    is IReaderFilter.Text -> TextBridge(filter)
    is IReaderFilter.Check -> if (filter.allowsExclusion) TriStateBridge(filter) else CheckBoxBridge(filter)
    is IReaderFilter.Select -> SelectBridge(filter)
    is IReaderFilter.Sort -> SortBridge(filter)
    is IReaderFilter.Group -> GroupBridge(filter)
    is IReaderFilter.Note -> Filter.Header(filter.name)
    else -> null
}

private class TextBridge(override val original: IReaderFilter.Text) :
    Filter.Text(original.name, original.value),
    IReaderBacked {
    override fun writeBack() {
        original.value = state
    }
}

// Unticked is left unset, as IReader starts it, rather than an explicit false.
private class CheckBoxBridge(override val original: IReaderFilter.Check) :
    Filter.CheckBox(original.name, original.value == true),
    IReaderBacked {
    override fun writeBack() {
        original.value = if (state) true else null
    }
}

// IReader's tri-state is a nullable Boolean: unset, included (true) or excluded (false).
private class TriStateBridge(override val original: IReaderFilter.Check) :
    Filter.TriState(
        original.name,
        when (original.value) {
            true -> STATE_INCLUDE
            false -> STATE_EXCLUDE
            null -> STATE_IGNORE
        },
    ),
    IReaderBacked {
    override fun writeBack() {
        original.value = when (state) {
            STATE_INCLUDE -> true
            STATE_EXCLUDE -> false
            else -> null
        }
    }
}

private class SelectBridge(override val original: IReaderFilter.Select) :
    Filter.Select<String>(original.name, original.options, original.value),
    IReaderBacked {
    override fun writeBack() {
        original.value = state
    }
}

private class SortBridge(override val original: IReaderFilter.Sort) :
    Filter.Sort(original.name, original.options, original.value?.let { Selection(it.index, it.ascending) }),
    IReaderBacked {
    override fun writeBack() {
        original.value = state?.let { IReaderFilter.Sort.Selection(it.index, it.ascending) }
    }
}

// Mihon's sheet draws a group's children whatever their types, so IReader's mixed groups carry over.
private class GroupBridge(override val original: IReaderFilter.Group) :
    Filter.Group<Filter<*>>(original.name, original.filters.mapNotNull(::toMihon)),
    IReaderBacked {
    override fun writeBack() {
        state.filterIsInstance<IReaderBacked>().forEach { it.writeBack() }
    }
}
