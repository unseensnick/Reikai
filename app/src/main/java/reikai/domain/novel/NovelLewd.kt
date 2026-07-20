package reikai.domain.novel

import reikai.domain.novel.model.Novel
import reikai.util.hasLewdGenre

/**
 * Adult-content check for the novel library lewd filter, the novel twin of [reikai.util.isLewd] for manga.
 * Novel sources expose no nsfw flag, so the only signal is the novel's adult genre tags; this reuses the
 * shared genre heuristic ([hasLewdGenre]) and has no source-name branch (that is manga-only).
 */
fun Novel.isLewd(): Boolean = hasLewdGenre(genre)
