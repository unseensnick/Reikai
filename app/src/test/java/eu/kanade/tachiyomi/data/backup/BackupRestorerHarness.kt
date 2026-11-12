package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.NovelRestorer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.protobuf.ProtoBuf
import reikai.data.backup.ApprovedRepos
import reikai.data.backup.RestoreExtensionInstaller
import reikai.data.local.CombineLocalVolumes
import reikai.domain.db.PassThroughTransactions
import reikai.domain.library.FakeLibraryManualOrderRepository
import reikai.domain.library.LibraryManualOrderRepository
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.AlignGroupCategories
import reikai.domain.merge.ReconcileMergedChapters
import reikai.novel.font.NovelFontManager
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.domain.source.service.SourceManager

/** Runs the real [BackupRestorer] over [backup] encoded as a file, every collaborator relaxed but those passed. */
suspend fun restoreEncoded(
    backup: Backup,
    options: RestoreOptions,
    categoriesRestorer: CategoriesRestorer = mockk(relaxed = true),
    mangaRestorer: MangaRestorer = mockk(relaxed = true),
    novelRestorer: NovelRestorer = mockk(relaxed = true),
    sourceManager: SourceManager = mockk(relaxed = true),
    stubSourceRepository: StubSourceRepository = mockk(relaxed = true),
    novelSourceManager: NovelSourceManager = mockk(relaxed = true),
    alignGroupCategories: AlignGroupCategories = mockk(relaxed = true),
    manualOrder: LibraryManualOrderRepository = FakeLibraryManualOrderRepository(),
    restoreExtensionInstaller: RestoreExtensionInstaller = mockk(relaxed = true),
    trusted: ApprovedRepos = ApprovedRepos(),
    combineLocalVolumes: CombineLocalVolumes = mockk(relaxed = true),
    novelFonts: NovelFontManager = mockk(relaxed = true),
) = restoreFile(
    ProtoBuf.encodeToByteArray(Backup.serializer(), backup), options, categoriesRestorer, mangaRestorer,
    novelRestorer, sourceManager, stubSourceRepository, novelSourceManager, alignGroupCategories, manualOrder,
    restoreExtensionInstaller, trusted, combineLocalVolumes, novelFonts,
)

/** Runs the real [BackupRestorer] over the backup file [file], as another app may have written it. */
suspend fun restoreFile(
    file: ByteArray,
    options: RestoreOptions,
    categoriesRestorer: CategoriesRestorer = mockk(relaxed = true),
    mangaRestorer: MangaRestorer = mockk(relaxed = true),
    novelRestorer: NovelRestorer = mockk(relaxed = true),
    sourceManager: SourceManager = mockk(relaxed = true),
    stubSourceRepository: StubSourceRepository = mockk(relaxed = true),
    novelSourceManager: NovelSourceManager = mockk(relaxed = true),
    alignGroupCategories: AlignGroupCategories = mockk(relaxed = true),
    manualOrder: LibraryManualOrderRepository = FakeLibraryManualOrderRepository(),
    restoreExtensionInstaller: RestoreExtensionInstaller = mockk(relaxed = true),
    trusted: ApprovedRepos = ApprovedRepos(),
    combineLocalVolumes: CombineLocalVolumes = mockk(relaxed = true),
    novelFonts: NovelFontManager = mockk(relaxed = true),
) {
    val uri = mockk<Uri>()
    val context = mockk<Context>(relaxed = true) {
        every { contentResolver.openInputStream(uri) } answers {
            file.inputStream()
        }
    }
    BackupRestorer(
        notifier = mockk(relaxed = true),
        isSync = false,
        context = context,
        downloadCache = mockk(relaxed = true),
        categoriesRestorer = categoriesRestorer,
        preferenceRestorer = mockk(relaxed = true),
        mangaRestorer = mangaRestorer,
        parser = ProtoBuf,
        sourceManager = sourceManager,
        stubSourceRepository = stubSourceRepository,
        novelSourceManager = novelSourceManager,
        novelRestorer = novelRestorer,
        feedRestorer = mockk(relaxed = true),
        reconcileMergedChapters = mockk<ReconcileMergedChapters> {
            coEvery { afterPass<Unit>(any()) } coAnswers { firstArg<suspend () -> Unit>().invoke() }
        },
        novelDownloadCache = mockk(relaxed = true),
        adultContentChecker = mockk {
            coEvery { adultIdsAmong(any()) } returns emptySet()
            coEvery { adultNovelIdsAmong(any()) } returns emptySet()
        },
        transactions = PassThroughTransactions,
        reikaiLibraryPreferences = ReikaiLibraryPreferences(InMemoryPreferenceStore()),
        alignGroupCategories = alignGroupCategories,
        restoreExtensionInstaller = restoreExtensionInstaller,
        restoreInstallSources = mockk(relaxed = true),
        manualOrder = manualOrder,
        novelFonts = novelFonts,
        combineLocalVolumes = combineLocalVolumes,
    ).restore(uri, options, trusted)
}
