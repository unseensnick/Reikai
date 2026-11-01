package reikai.domain.entry

import dev.zacsweers.metro.Inject
import reikai.domain.novel.NovelRepository
import tachiyomi.domain.manga.interactor.ResetViewerFlags

/** Clears the reader settings of every series of both content types, the one Settings action does both. */
@Inject
class ResetEntryViewerFlags(
    private val resetMangaFlags: ResetViewerFlags,
    private val novelRepository: NovelRepository,
) {
    /** False when either failed; each runs regardless, so a failure on one type leaves the other reset. */
    suspend fun await(): Boolean {
        val manga = resetMangaFlags.await()
        val novels = novelRepository.resetViewerFlags()
        return manga && novels
    }
}
