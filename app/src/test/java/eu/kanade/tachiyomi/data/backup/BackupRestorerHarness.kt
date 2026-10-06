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
import reikai.domain.db.PassThroughTransactions
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.ReconcileMergedChapters
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/** Runs the real [BackupRestorer] over [backup] encoded as a file, every collaborator relaxed but those passed. */
suspend fun restoreEncoded(
    backup: Backup,
    options: RestoreOptions,
    categoriesRestorer: CategoriesRestorer = mockk(relaxed = true),
    mangaRestorer: MangaRestorer = mockk(relaxed = true),
    novelRestorer: NovelRestorer = mockk(relaxed = true),
) {
    val uri = mockk<Uri>()
    val context = mockk<Context>(relaxed = true) {
        every { contentResolver.openInputStream(uri) } answers {
            ProtoBuf.encodeToByteArray(Backup.serializer(), backup).inputStream()
        }
    }
    BackupRestorer(
        notifier = mockk(relaxed = true),
        isSync = false,
        context = context,
        downloadCache = mockk(relaxed = true),
        categoriesRestorer = categoriesRestorer,
        preferenceRestorer = mockk(relaxed = true),
        extensionStoreRestorer = mockk(relaxed = true),
        mangaRestorer = mangaRestorer,
        parser = ProtoBuf,
        novelRestorer = novelRestorer,
        extensionRestorer = mockk(relaxed = true),
        feedRestorer = mockk(relaxed = true),
        novelPluginRestorer = mockk(relaxed = true),
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
    ).restore(uri, options)
}
