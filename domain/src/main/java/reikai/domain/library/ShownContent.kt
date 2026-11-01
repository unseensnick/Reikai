package reikai.domain.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

/** The Show setting: which content types the app shows. [ContentType.ALL] shows both, its default. */
fun PreferenceStore.shownContent(): Preference<ContentType> = getEnum("shown_content", ContentType.ALL)

/** What this chip selection means under the Show setting: the shown type, whatever the chip last held. */
fun ContentType.within(shown: ContentType): ContentType = if (shown == ContentType.ALL) this else shown

/**
 * A surface's chip selection read through the Show setting, so every reader of the chip follows it and
 * none has to ask. The stored choice is left alone while a type is hidden, so it comes back on All.
 */
class ShownContentPreference(
    private val stored: Preference<ContentType>,
    private val shown: Preference<ContentType>,
) : Preference<ContentType> by stored {

    override fun get(): ContentType = stored.get().within(shown.get())

    override fun set(value: ContentType) {
        if (shown.get() == ContentType.ALL) stored.set(value)
    }

    override fun changes(): Flow<ContentType> = combine(stored.changes(), shown.changes(), ContentType::within)

    override fun stateIn(scope: CoroutineScope): StateFlow<ContentType> =
        changes().stateIn(scope, SharingStarted.Eagerly, get())
}
