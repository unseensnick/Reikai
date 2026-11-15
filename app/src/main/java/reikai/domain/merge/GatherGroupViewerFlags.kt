package reikai.domain.merge

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import reikai.domain.library.ContentType
import reikai.domain.manga.MangaMergeManager
import reikai.domain.novel.NovelHistoryRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelPageLayout
import reikai.domain.novel.NovelRenderingMode
import reikai.domain.novel.NovelRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.repository.MangaRepository
import kotlin.time.Instant

/**
 * Gives every group of one content type the reader flags the readers read from the group
 * ([GroupViewerFlags]): each field takes the value of the most recently read member that chose one,
 * written to every member. For the writes that left members disagreeing: the upgrade that gathered
 * flags kept per member, and a restore of a backup made before it.
 */
@Inject
class GatherGroupViewerFlags(
    private val mangaMergeManager: MangaMergeManager,
    private val mangaRepository: MangaRepository,
    private val historyRepository: HistoryRepository,
    private val novelMergeManager: NovelMergeManager,
    private val novelRepository: NovelRepository,
    private val novelHistoryRepository: NovelHistoryRepository,
) {

    suspend fun gather(contentType: ContentType) {
        when (contentType) {
            ContentType.MANGA -> gather(
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
            ContentType.NOVELS -> gather(
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
            ContentType.ALL -> error("A group belongs to one content type")
        }
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
