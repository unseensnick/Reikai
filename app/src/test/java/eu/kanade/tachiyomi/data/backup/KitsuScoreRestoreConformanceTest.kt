package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupKitsuNativeScale
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelTracking
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.NovelRestorer
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * The Kitsu score each type's restorer receives from an encoded backup: doubled from a backup written
 * before the 2-20 scale, kept from one marked as on it or holding a Kitsu score only that scale produces.
 * Runs the real BackupRestorer, so the decision is made once over both types' tracks.
 */
class KitsuScoreRestoreConformanceTest {

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `an old backup's Kitsu score is doubled`(type: Type) = runTest {
        restoredKitsuScore(type, Backup(backupManga = emptyList()).with(type, 7.5)) shouldBe 15.0
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a backup marked as on Kitsu's scale keeps its Kitsu score`(type: Type) = runTest {
        val backup = Backup(backupManga = emptyList(), backupKitsuNativeScale = BackupKitsuNativeScale())
        restoredKitsuScore(type, backup.with(type, 7.5)) shouldBe 7.5
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `the other type's Kitsu score above 10 keeps this type's score`(type: Type) = runTest {
        val other = Type.entries.single { it != type }
        restoredKitsuScore(type, Backup(backupManga = emptyList()).with(type, 7.5).with(other, 16.0)) shouldBe 7.5
    }

    private suspend fun restoredKitsuScore(type: Type, backup: Backup): Double {
        val manga = mutableListOf<BackupManga>()
        val novels = mutableListOf<BackupNovel>()
        restoreEncoded(
            backup,
            RestoreOptions(libraryEntries = true, categories = false, appSettings = false),
            mangaRestorer = mockk<MangaRestorer>(relaxed = true) {
                coEvery { restore(any(), any()) } coAnswers { manga += firstArg<List<BackupManga>>() }
            },
            novelRestorer = mockk<NovelRestorer>(relaxed = true) {
                coEvery { restore(any(), any()) } coAnswers { novels += firstArg<BackupNovel>() }
            },
        )
        return when (type) {
            Type.MANGA -> manga.single().tracking.single().score.toDouble()
            Type.NOVELS -> novels.single().tracking.single().score
        }
    }

    private fun Backup.with(type: Type, kitsuScore: Double): Backup = when (type) {
        Type.MANGA -> copy(
            backupManga = listOf(
                BackupManga(
                    source = 1,
                    url = "a",
                    tracking = listOf(BackupTracking(syncId = KITSU, libraryId = 1, score = kitsuScore.toFloat())),
                ),
            ),
        )
        Type.NOVELS -> copy(
            backupNovels = listOf(
                BackupNovel(
                    source = "s",
                    url = "a",
                    tracking = listOf(BackupNovelTracking(trackerId = KITSU.toLong(), score = kitsuScore)),
                ),
            ),
        )
    }

    enum class Type { MANGA, NOVELS }

    private companion object {
        const val KITSU = 3
    }
}
