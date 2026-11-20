package reikai.presentation.widget

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import reikai.domain.entry.EntryId
import reikai.domain.entry.GetEntryCustomInfo
import reikai.domain.entry.withCustomInfo
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.MangaMergeManager
import reikai.domain.merge.onePerSeries
import tachiyomi.domain.updates.model.UpdatesWithRelations

/** [MangaUpdatesPerSeries] over the manga merge groups and Edit info, the rules the combined widget draws by. */
@Inject
@ContributesBinding(AppScope::class)
class MergedMangaUpdatesPerSeries(
    private val mangaMergeManager: MangaMergeManager,
    private val getEntryCustomInfo: GetEntryCustomInfo,
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences,
) : MangaUpdatesPerSeries {
    override suspend fun onePerSeries(updates: List<UpdatesWithRelations>): List<UpdatesWithRelations> =
        mangaMergeManager.onePerSeries(updates) { it.mangaId }

    override fun withCustomInfo(updates: Flow<List<UpdatesWithRelations>>): Flow<List<UpdatesWithRelations>> =
        combine(updates, getEntryCustomInfo.subscribeAll()) { rows, customInfo ->
            rows.map { it.withCustomInfo(customInfo[EntryId.Manga(it.mangaId)]) }
        }

    override val seriesMerging: Flow<Boolean>
        get() = reikaiLibraryPreferences.seriesMergingEnabled.changes()
}
