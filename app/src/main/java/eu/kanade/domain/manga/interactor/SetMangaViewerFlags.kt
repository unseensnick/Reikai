package eu.kanade.domain.manga.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import tachiyomi.domain.manga.repository.MangaRepository

@Inject
class SetMangaViewerFlags(
    private val mangaRepository: MangaRepository,
) {

    // RK -->
    // A read here and a write back let two quick picks, each from its own launch, undo each other; the
    // repository writes the masked bits in one statement instead (the novel side's SetNovelViewerFlags too).
    suspend fun awaitSetReadingMode(id: Long, flag: Long) {
        mangaRepository.setViewerFlags(id, flag, ReadingMode.MASK.toLong())
    }

    suspend fun awaitSetOrientation(id: Long, flag: Long) {
        mangaRepository.setViewerFlags(id, flag, ReaderOrientation.MASK.toLong())
    }
    // RK <--
}
