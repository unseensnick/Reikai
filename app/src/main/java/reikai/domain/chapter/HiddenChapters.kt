package reikai.domain.chapter

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * The key a chapter the user hid is stored under, for manga and novels alike: the source of the entry
 * that owns the copy and the chapter's url, because a backup restore keeps those where row ids change.
 * Every reader of the hidden set builds its key here, so a site cannot drift to a key nothing stores.
 */
fun hiddenChapterKey(source: String, url: String): String = "$source|$url"

/**
 * A manga chapter's hidden key. [owner] is the manga holding this copy, which in a merge group is the
 * member it came from rather than the entry the screen opened.
 */
fun Chapter.hiddenKey(owner: Manga): String = hiddenChapterKey(owner.source.toString(), url)
