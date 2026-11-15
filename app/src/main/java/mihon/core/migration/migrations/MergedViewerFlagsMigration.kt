package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.domain.manga.MangaMergeManager
import reikai.domain.merge.EntryMergeManager
import reikai.domain.merge.GroupViewerFlags
import reikai.domain.novel.NovelHistoryRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelPageLayout
import reikai.domain.novel.NovelRenderingMode
import reikai.domain.novel.NovelRepository
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.repository.MangaRepository
import kotlin.time.Instant

/**
 * One-time gathering of reader flags a merged series kept on whichever member a reader opened
 * through, before both readers read them from the group ([GroupViewerFlags]): each field takes the
 * value of the most recently read member that chose one, written to every member. Best-effort per
 * content type, so a failure on one side does not block startup or the other side.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class MergedViewerFlagsMigration(
    private val mangaMergeManager: MangaMergeManager,
    private val mangaRepository: MangaRepository,
    private val historyRepository: HistoryRepository,
    private val novelMergeManager: NovelMergeManager,
    private val novelRepository: NovelRepository,
    private val novelHistoryRepository: NovelHistoryRepository,
) : Migration {
    override val version: Float = 209f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        runCatching {
            gather(
                mangaMergeManager,
                masks = listOf(ReadingMode.MASK.toLong(), ReaderOrientation.MASK.toLong()),
                member = { id ->
                    GroupViewerFlags.Member(
                        mangaRepository.getMangaById(id).viewerFlags,
                        lastReadAt(historyRepository.getHistoryByMangaId(id).map { it.readAt }),
                    )
                },
                write = mangaRepository::setViewerFlags,
            )
        }.onFailure { logcat(LogPriority.ERROR, it) { "Gathering merged manga reader flags failed" } }
        runCatching {
            gather(
                novelMergeManager,
                masks = listOf(ReaderOrientation.MASK.toLong(), NovelRenderingMode.MASK, NovelPageLayout.MASK),
                member = { id ->
                    GroupViewerFlags.Member(
                        novelRepository.getById(id)?.viewerFlags ?: 0L,
                        lastReadAt(novelHistoryRepository.getHistoryByNovelId(id).map { it.readAt }),
                    )
                },
                write = novelRepository::setViewerFlags,
            )
        }.onFailure { logcat(LogPriority.ERROR, it) { "Gathering merged novel reader flags failed" } }
        true
    }

    private suspend fun gather(
        mergeManager: EntryMergeManager,
        masks: List<Long>,
        member: suspend (id: Long) -> GroupViewerFlags.Member,
        write: suspend (id: Long, flag: Long, mask: Long) -> Boolean,
    ) {
        mergeManager.libraryGroups().filter { it.size > 1 }.forEach { ids ->
            val group = GroupViewerFlags.of(ids.first(), ids)
            GroupViewerFlags.chosen(ids.map { member(it) }, masks).forEach { (mask, flag) ->
                group.write { write(it, flag, mask) }
            }
        }
    }

    private fun lastReadAt(readAts: List<Instant?>): Long =
        readAts.maxOfOrNull { it?.toEpochMilliseconds() ?: 0L } ?: 0L
}
