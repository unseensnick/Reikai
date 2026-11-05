package reikai.domain.library

import tachiyomi.core.metadata.comicinfo.localLanguageIn
import tachiyomi.domain.manga.model.Manga

/** The language a local manga's files name (ComicInfo `LanguageISO`, or details.json), as sources write it. */
val Manga.localFilesLanguage: String?
    get() = localLanguage(localLanguageIn(memo))
