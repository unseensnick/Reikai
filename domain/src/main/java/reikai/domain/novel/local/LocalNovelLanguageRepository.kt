package reikai.domain.novel.local

import kotlinx.coroutines.flow.Flow

/** The language each local novel's files name, by series path. See `data/local_novel_language.sq`. */
interface LocalNovelLanguageRepository {

    /**
     * Keeps the series at [url] in the language its book's tag [language] names, as the code sources use
     * ([reikai.domain.library.localLanguage]), or forgets it when the tag names none.
     */
    suspend fun set(url: String, language: String?)

    /** Every local series' language, by series path. */
    fun subscribeAll(): Flow<Map<String, String>>

    suspend fun getAll(): Map<String, String>

    suspend fun get(url: String): String?
}
