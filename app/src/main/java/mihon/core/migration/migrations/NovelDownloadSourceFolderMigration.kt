package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.domain.novel.NovelPreferences
import reikai.novel.download.NovelDownloadCache
import reikai.novel.download.NovelDownloadProvider
import tachiyomi.core.common.util.lang.withIOContext

/**
 * One-time move of each novel source's download folder from its bare id (`tachiyomi_123`, `chikari`) to the
 * name [NovelDownloadProvider] gives it now (`Novel Site (EN)`, `chikari (Chikari)`), so an upgrader's
 * downloads are not read as missing. A folder whose new name is taken, by a catalogue of the same name and
 * language, is merged into it, since both now read from that one folder.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class NovelDownloadSourceFolderMigration(
    private val novelPreferences: NovelPreferences,
    private val provider: NovelDownloadProvider,
    private val cache: NovelDownloadCache,
) : Migration {
    override val version: Float = 208f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        val sources = novelPreferences.seenNovelSources().get().keys
        provider.renameSourceFolders(sources, from = { it }, to = provider::sourceFolderLabel)
        provider.mergeSourceFolders(sources, from = { it }, to = provider::sourceFolderLabel)
        cache.invalidate()
        true
    }
}
