package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupMangaMergeGroup
import eu.kanade.tachiyomi.data.backup.models.BackupMangaSourceRef
import eu.kanade.tachiyomi.data.backup.models.BackupMergeGroupsStored
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelMergeGroup
import eu.kanade.tachiyomi.data.backup.models.BackupNovelSourceRef
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.NovelRestorer
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.merge.PrefEraGrouping

/**
 * What a restore hands each type's merge rebuild from the backup file itself: a backup without the
 * stored-groups marker (0.3.x) passes its favourites, its unmerge pairs and its own same-title switch,
 * and one with the marker passes nothing. Runs the real BackupRestorer over an encoded backup.
 */
class PrefEraRestoreConformanceTest {

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a pref-era backup hands its favourites to the rebuild`(type: Type) = runTest {
        restore(type, type.backup())!!.favorites.map { it.title } shouldBe listOf("Kept", "Kept")
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a pref-era backup's non-favourite is not a same-title candidate`(type: Type) = runTest {
        restore(type, type.backup(secondIsFavorite = false))!!.favorites.size shouldBe 1
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a pref-era backup hands its unmerge pairs to the rebuild`(type: Type) = runTest {
        restore(type, type.backup())!!.unmerges.size shouldBe 1
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a pref-era backup's own same-title switch reaches the rebuild`(type: Type) = runTest {
        restore(type, type.backup(sameTitleSwitch = false))!!.switches.autoMergeByTitle shouldBe false
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a pref-era backup without app settings reads the old default`(type: Type) = runTest {
        restore(type, type.backup(sameTitleSwitch = null))!!.switches.autoMergeByTitle shouldBe true
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a backup with the stored-groups marker restores its groups as stored`(type: Type) = runTest {
        restore(type, type.backup().apply { backupMergeGroupsStored = BackupMergeGroupsStored() }).shouldBeNull()
    }

    private suspend fun restore(type: Type, backup: Backup): PrefEraGrouping<*>? {
        val mangaEra = slot<PrefEraGrouping<BackupMangaSourceRef>?>()
        val novelEra = slot<PrefEraGrouping<BackupNovelSourceRef>?>()
        val mangaRestorer = mockk<MangaRestorer>(relaxed = true) {
            coEvery { restoreMerges(any(), captureNullable(mangaEra)) } returns Unit
        }
        val novelRestorer = mockk<NovelRestorer>(relaxed = true) {
            coEvery { restoreMerges(any(), captureNullable(novelEra)) } returns Unit
        }
        restoreEncoded(
            backup,
            RestoreOptions(libraryEntries = true, categories = false, appSettings = false),
            mangaRestorer = mangaRestorer,
            novelRestorer = novelRestorer,
        )
        return when (type) {
            Type.MANGA -> mangaEra.captured
            Type.NOVELS -> novelEra.captured
        }
    }

    /** Two same-title favourites on two sources, split by an unmerge pair, as a 0.3.x backup wrote them. */
    enum class Type(private val sameTitleKey: String) {
        MANGA("auto_merge_same_title") {
            override fun entries(secondIsFavorite: Boolean) = Backup(
                backupManga = listOf(
                    BackupManga(source = 1, url = "a", title = "Kept"),
                    BackupManga(source = 2, url = "b", title = "Kept", favorite = secondIsFavorite),
                ),
                backupMangaUnmerges = listOf(
                    BackupMangaMergeGroup(listOf(BackupMangaSourceRef("a", 1), BackupMangaSourceRef("b", 2))),
                ),
            )
        },
        NOVELS("novel_auto_merge_same_title") {
            override fun entries(secondIsFavorite: Boolean) = Backup(
                backupManga = emptyList(),
                backupNovels = listOf(
                    BackupNovel(source = "s1", url = "a", title = "Kept"),
                    BackupNovel(source = "s2", url = "b", title = "Kept", favorite = secondIsFavorite),
                ),
                backupNovelUnmerges = listOf(
                    BackupNovelMergeGroup(listOf(BackupNovelSourceRef("a", "s1"), BackupNovelSourceRef("b", "s2"))),
                ),
            )
        },
        ;

        protected abstract fun entries(secondIsFavorite: Boolean): Backup

        fun backup(secondIsFavorite: Boolean = true, sameTitleSwitch: Boolean? = true): Backup =
            entries(secondIsFavorite).apply {
                backupPreferences = listOfNotNull(
                    sameTitleSwitch?.let { BackupPreference(sameTitleKey, BooleanPreferenceValue(it)) },
                )
            }
    }
}
