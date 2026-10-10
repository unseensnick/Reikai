package reikai.presentation.details

import reikai.domain.entry.withCustomInfo
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.withCustomInfo
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga

/**
 * Whether the details header names the whole merged group rather than one source: the unified view of
 * a group, where the label reads "All" and a library search by one source would pick a member at random.
 */
fun headerNamesWholeGroup(sourceCount: Int, selectedSource: Long?): Boolean = sourceCount > 1 && selectedSource == null

/**
 * The one entry the details page describes, feeding its header, synopsis and tags alike: the selected
 * [sibling] chip's own entry (never the anchor's own chip), else the [anchor], with the custom-info
 * [overlay] applied either way, since Edit info writes the anchor's row. A sibling keeps its own cover
 * through [ownCover], with its own row's cover address: the image its cover viewer opens. Library state
 * (favourite, notes) and every write stay on the anchor.
 */
fun <T> shownEntry(anchor: T, sibling: T?, overlay: (T) -> T, ownCover: (shown: T, sibling: T) -> T): T =
    sibling?.let { ownCover(overlay(it), it) } ?: overlay(anchor)

/** [shownEntry] for manga. [siblingInfo] counts only when read for that sibling, never a chip left behind. */
fun shownManga(anchor: Manga, sibling: Manga?, anchorInfo: CustomMangaInfo?, siblingInfo: CustomMangaInfo?): Manga =
    shownEntry(anchor, sibling, { it.withCustomInfo(anchorInfo) }) { shown, own ->
        shown.copy(thumbnailUrl = own.withCustomInfo(siblingInfo?.takeIf { it.mangaId == own.id }).thumbnailUrl)
    }

/** [shownEntry] for novels. [siblingInfo] counts only when read for that sibling, never a chip left behind. */
fun shownNovel(anchor: Novel, sibling: Novel?, anchorInfo: CustomNovelInfo?, siblingInfo: CustomNovelInfo?): Novel =
    shownEntry(anchor, sibling, { it.withCustomInfo(anchorInfo) }) { shown, own ->
        shown.copy(thumbnailUrl = own.withCustomInfo(siblingInfo?.takeIf { it.novelId == own.id }).thumbnailUrl)
    }

/**
 * The member a merged entry's All view downloads through, opens on the web and takes its update interval
 * from: the [anchor] while its source is installed, else the first installed member in [group] order.
 * With none installed it stays the [anchor], whose source the page then reports as missing.
 */
fun <T> unifiedViewMember(anchor: T, group: List<T>, isInstalled: (T) -> Boolean): T =
    if (isInstalled(anchor)) anchor else group.firstOrNull(isInstalled) ?: anchor
