package reikai.domain.novel.local

import kotlinx.coroutines.flow.flowOf

/** A local novel language store holding nothing, for a test whose subject is not a local novel's language. */
object NoLocalNovelLanguages : LocalNovelLanguageRepository {
    override suspend fun set(url: String, language: String?) = Unit

    override fun subscribeAll() = flowOf(emptyMap<String, String>())

    override suspend fun getAll() = emptyMap<String, String>()

    override suspend fun get(url: String): String? = null
}
