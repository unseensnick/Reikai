package reikai.data.coil

import coil3.key.Keyer
import coil3.request.Options

/** Coil cache key for [NovelCover]. Novels have no custom-cover override, so the key is just the
 *  cover URL plus its last-modified stamp (the novel twin of [eu.kanade.tachiyomi.data.coil.MangaCoverKeyer]). */
class NovelCoverKeyer : Keyer<NovelCover> {
    override fun key(data: NovelCover, options: Options): String = "${data.url};${data.lastModified}"
}
