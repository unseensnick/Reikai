package reikai.domain.library

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import tachiyomi.core.metadata.comicinfo.LOCAL_LANGUAGE_MEMO_KEY
import tachiyomi.domain.manga.model.Manga

/** The language a local manga's files name (ComicInfo `LanguageISO`, or details.json), as sources write it. */
val Manga.localFilesLanguage: String?
    get() = localLanguage(memo[LOCAL_LANGUAGE_MEMO_KEY]?.jsonPrimitive?.contentOrNull)
