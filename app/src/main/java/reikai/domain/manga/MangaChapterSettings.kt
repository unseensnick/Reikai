package reikai.domain.manga

import dev.zacsweers.metro.Inject
import reikai.domain.merge.GroupChapterSettings
import reikai.domain.merge.withOwnerChapterFlags
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

/** Manga's [GroupChapterSettings], over each member's own chapter flags. */
@Inject
class MangaChapterSettings(mangaRepository: MangaRepository) : GroupChapterSettings<Manga>(
    flagsOf = { it.chapterFlags },
    withFlags = { copy(chapterFlags = it) },
    load = { mangaRepository.getMangaById(it) },
    write = { ids, flags -> mangaRepository.updateAll(ids.map { MangaUpdate(it) { chapterFlags = flags } }) },
)

/** Manga's reader flags (reading mode, rotation) handed over as [GroupChapterSettings] hands chapter flags. */
@Inject
class MangaViewerFlagSettings(mangaRepository: MangaRepository) : GroupChapterSettings<Manga>(
    flagsOf = { it.viewerFlags },
    withFlags = { copy(viewerFlags = it) },
    load = { mangaRepository.getMangaById(it) },
    write = { ids, flags -> mangaRepository.updateAll(ids.map { MangaUpdate(it) { viewerFlags = flags } }) },
)

/** [this] carrying its merge group's chapter settings, given the group's members in order. */
fun Manga.withGroupChapterFlags(members: Collection<Manga>): Manga =
    withOwnerChapterFlags(members, { it.chapterFlags }) { copy(chapterFlags = it) }

/** [opened] carrying this group's chapter settings; [opened] itself outside a group. */
fun MergedChapterProvider.Group?.chapterSettingsOf(opened: Manga): Manga =
    opened.withGroupChapterFlags(this?.mangaById?.values.orEmpty())
