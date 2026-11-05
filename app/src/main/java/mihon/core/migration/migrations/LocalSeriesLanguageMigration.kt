package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import mihon.domain.source.interactor.UpdateMangaFromRemote
import reikai.domain.novel.NovelRepository
import reikai.novel.local.LocalNovelSource
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.source.local.isLocal

/**
 * One-time read of every local series in the library, of both types, so each picks up the language its
 * files name, which is otherwise captured only by a details read. Goes through each local source's own
 * details read (the manga memo, the novel language table). Launched on the app scope, which runs on IO,
 * rather than awaited in the chain, since startup waits on the chain and a large local library is slow.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class LocalSeriesLanguageMigration(
    private val mangaRepository: MangaRepository,
    private val novelRepository: NovelRepository,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    private val localNovelSource: LocalNovelSource,
    @AppCoroutineScope private val appScope: CoroutineScope,
) : Migration {
    override val version: Float = 206f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        appScope.launch {
            // Failures are logged inside, and one series failing leaves the rest to run.
            mangaRepository.getFavorites().filter { it.isLocal() }.forEach { manga ->
                updateMangaFromRemote(manga, fetchDetails = true)
            }
            novelRepository.getFavorites().filter { LocalNovelSource.isLocal(it.source) }.forEach { novel ->
                runCatchingCancellable { localNovelSource.parseNovel(novel.url) }
                    .onFailure { logcat(LogPriority.ERROR, it) { "Reading local novel ${novel.url} failed" } }
            }
        }
        return true
    }
}
