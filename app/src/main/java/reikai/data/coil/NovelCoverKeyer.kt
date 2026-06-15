package reikai.data.coil

import coil3.key.Keyer
import coil3.request.Options

/** Coil cache key for [NovelCover] (the novel twin of [eu.kanade.tachiyomi.data.coil.MangaCoverKeyer]).
 *  Includes the novel id so a custom cover keys distinctly from the source cover, plus the last-modified
 *  stamp so setting/deleting a custom cover (which bumps it) busts the cache. */
class NovelCoverKeyer : Keyer<NovelCover> {
    override fun key(data: NovelCover, options: Options): String = "${data.novelId};${data.url};${data.lastModified}"
}
