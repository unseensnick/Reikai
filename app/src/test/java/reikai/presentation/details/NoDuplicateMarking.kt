package reikai.presentation.details

import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.chapter.NoLocalVolumes
import reikai.domain.chapter.VolumePickRepository
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

/** Volume picks held in memory, for a details test whose subject is not volumes. */
fun noVolumePicking() = VolumePicking(
    object : VolumePickRepository {
        override fun picks(type: ContentType) = MutableStateFlow(emptyMap<Long, String>())

        override suspend fun set(type: ContentType, entryId: Long, volumeKey: String?) = Unit
    },
    NoLocalVolumes,
    { _, _, _ -> null },
    mockk(relaxed = true),
    mockk(relaxed = true),
    mockk(),
    CoroutineScope(Dispatchers.Unconfined),
)
