package reikai.data.novel.local

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import reikai.domain.novel.local.LocalNovelLanguageRepository
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class LocalNovelLanguageRepositoryImpl(
    database: Database,
) : LocalNovelLanguageRepository {

    private val queries = database.local_novel_languageQueries

    override suspend fun set(url: String, language: String?) {
        if (language == null) queries.clearLanguage(url) else queries.setLanguage(url, language)
    }

    override fun subscribeAll(): Flow<Map<String, String>> =
        queries.findAll().subscribeToList().map { rows -> rows.associate { it.url to it.language } }

    override suspend fun getAll(): Map<String, String> =
        queries.findAll().awaitAsList().associate { it.url to it.language }
}
