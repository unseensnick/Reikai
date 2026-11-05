package reikai.domain.novel.local

import kotlinx.coroutines.flow.Flow

/** The language each local novel's files name, by series path. See `data/local_novel_language.sq`. */
interface LocalNovelLanguageRepository {

    /** Keeps [language] for the series at [url], or forgets it when its files name none. */
    suspend fun set(url: String, language: String?)

    /** Every local series' language, by series path. */
    fun subscribeAll(): Flow<Map<String, String>>

    suspend fun getAll(): Map<String, String>
}
