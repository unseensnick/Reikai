package reikai.presentation.details

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.library.ContentType
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/** A marking with duplicates switched off, for a details test whose subject is not them. */
fun noDuplicateMarking(): DuplicateMarking {
    val store = InMemoryPreferenceStore()
    val scope = CoroutineScope(Dispatchers.Unconfined)
    return DuplicateMarking(
        ContentType.MANGA,
        InMemoryChapterCopyMarks(),
        store.getBoolean("show_skipped_duplicates", false),
        store.getBoolean("skip_dupe", false),
        scope,
        scope,
    )
}
