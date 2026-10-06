package reikai.novel.content

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.util.system.logcat

/** One entry of a list the user writes for the reader, kept by an id an edit does not change. */
interface NovelStoredItem<T : NovelStoredItem<T>> {
    val id: String
    val title: String
    val enabled: Boolean

    fun toggled(): T
}

/**
 * The JSON codec for one stored list. Unknown keys are ignored so a list written by a newer build still
 * reads on an older one: a strict decode throws there, which reads as an empty list, and the next save
 * would write that emptiness back over everything the user had.
 */
open class NovelStoredListCodec<T>(serializer: KSerializer<T>, private val what: String) {
    private val listSerializer = ListSerializer(serializer)

    /** The stored list, or empty for one that will not read, which the next save then replaces. */
    fun decode(json: String): List<T> = try {
        lenientJson.decodeFromString(listSerializer, json)
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "Failed to parse $what" }
        emptyList()
    }

    fun encode(items: List<T>): String = lenientJson.encodeToString(listSerializer, items)

    private companion object {
        val lenientJson = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Saving, deleting and switching the entries of one stored list. Each change reads the list back from
 * [preference] rather than from what a screen last showed, so a save cannot drop an edit made while its
 * dialog was open.
 */
class NovelStoredToggleList<T : NovelStoredItem<T>>(
    private val preference: Preference<String>,
    private val codec: NovelStoredListCodec<T>,
) {
    val items: Flow<List<T>> = preference.changes().map(codec::decode)

    /** Replaces the entry sharing [item]'s id where it stands, or appends [item] when it is new. */
    fun save(item: T) = update { items ->
        if (items.any { it.id == item.id }) items.map { if (it.id == item.id) item else it } else items + item
    }

    fun delete(item: T) = update { items -> items.filterNot { it.id == item.id } }

    fun toggle(item: T) = update { items -> items.map { if (it.id == item.id) it.toggled() else it } }

    private inline fun update(change: (List<T>) -> List<T>) {
        preference.set(codec.encode(change(codec.decode(preference.get()))))
    }
}
