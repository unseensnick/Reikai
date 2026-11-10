package tachiyomi.source.local.image

import android.content.Context
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.model.SManga
import reikai.storage.localCover
import reikai.storage.writeLocalCover
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.InputStream

@Inject
@SingleIn(AppScope::class)
class LocalCoverManager(
    private val context: Context,
    private val fileSystem: LocalSourceFileSystem,
) {

    fun find(mangaUrl: String): UniFile? {
        // RK: the rule moved to localCover, which the local novel source reads too
        return fileSystem.getFilesInMangaDirectory(mangaUrl).localCover()
    }

    fun update(
        manga: SManga,
        inputStream: InputStream,
    ): UniFile? {
        val directory = fileSystem.getMangaDirectory(manga.url)
        if (directory == null) {
            inputStream.close()
            return null
        }

        // RK: the write moved to writeLocalCover, which the local novel source writes through too
        val targetFile = writeLocalCover(context, directory, inputStream)

        manga.thumbnail_url = targetFile.uri.toString()
        return targetFile
    }
}
