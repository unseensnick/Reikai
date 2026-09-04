package eu.kanade.tachiyomi.data.backup.create.creators

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.source.Source
import tachiyomi.domain.source.service.SourceManager

@Inject
class SourcesBackupCreator(
    private val sourceManager: SourceManager,
) {

    suspend operator fun invoke(mangas: List<BackupManga>): List<BackupSource> {
        return mangas
            .map(BackupManga::source)
            .distinct()
            .map { sourceManager.getOrStub(it).toBackupSource() }
    }

    // RK: build the source list from ids collected during the streaming manga pass, so the whole
    // List<BackupManga> never has to be resident just to derive sources.
    suspend fun forSourceIds(sourceIds: Set<Long>): List<BackupSource> {
        return sourceIds
            .map { sourceManager.getOrStub(it).toBackupSource() }
    }
}

private fun Source.toBackupSource() =
    BackupSource(
        name = this.name,
        sourceId = this.id,
    )
