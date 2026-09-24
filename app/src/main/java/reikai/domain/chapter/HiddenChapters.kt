package reikai.domain.chapter

/**
 * The key a chapter the user hid is stored under, for manga and novels alike: the source of the entry
 * that owns the copy and the chapter's url, because a backup restore keeps those where row ids change.
 * Every reader of the hidden set builds its key here, so a site cannot drift to a key nothing stores.
 */
fun hiddenChapterKey(source: String, url: String): String = "$source|$url"
