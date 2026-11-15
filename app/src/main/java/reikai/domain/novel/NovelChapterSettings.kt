package reikai.domain.novel

import dev.zacsweers.metro.Inject
import reikai.domain.merge.GroupChapterSettings
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelUpdate

/** Novels' [GroupChapterSettings], over each member's own chapter flags, local-override bits included. */
@Inject
class NovelChapterSettings(novelRepository: NovelRepository) : GroupChapterSettings<Novel>(
    flagsOf = { it.chapterFlags },
    withFlags = { copy(chapterFlags = it) },
    load = { novelRepository.getById(it) },
    write = { ids, flags -> novelRepository.updateAll(ids.map { NovelUpdate(it) { chapterFlags = flags } }) },
)

/** Novels' reader flags (rotation, rendering mode, reading mode) handed over as [GroupChapterSettings] hands chapter flags. */
@Inject
class NovelViewerFlagSettings(novelRepository: NovelRepository) : GroupChapterSettings<Novel>(
    flagsOf = { it.viewerFlags },
    withFlags = { copy(viewerFlags = it) },
    load = { novelRepository.getById(it) },
    write = { ids, flags -> novelRepository.updateAll(ids.map { NovelUpdate(it) { viewerFlags = flags } }) },
)
