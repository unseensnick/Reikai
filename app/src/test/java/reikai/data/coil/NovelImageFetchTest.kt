package reikai.data.coil

import coil3.disk.DiskCache
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reikai.novel.local.LocalNovelPicture
import reikai.novel.local.LocalNovelSource
import reikai.novel.network.NovelImageRequests
import java.io.File
import java.io.FileNotFoundException

/** A chapter picture fetched through Coil's real disk cache. */
class NovelImageFetchTest {

    @TempDir
    lateinit var root: File

    private val address = LocalNovelSource.pictureAddress("Saga", "Book.epub", "OEBPS/plate.png", 1)

    /** Only the local source reads the user's books, whether the picture is cached or not. */
    @Test
    fun `a cached local book's picture is not served to another source's chapter`() = runTest {
        val cache = DiskCache.Builder().directory(root.toOkioPath()).build()
        val requests = mockk<NovelImageRequests> {
            coEvery { localPicture(any(), any()) } returns null
            coEvery { localPicture(address, LocalNovelSource.ID) } returns
                LocalNovelPicture(byteArrayOf(1), "image/png")
        }
        fetchNovelImage(NovelImage(address, LocalNovelSource.ID), requests, cache, readCache = true, writeCache = true)
            .source.close()

        try {
            shouldThrow<FileNotFoundException> {
                fetchNovelImage(NovelImage(address, "lnreader.x"), requests, cache, readCache = true, writeCache = true)
                    .source.close()
            }
        } finally {
            cache.shutdown()
        }
    }

    /** Refused before the disk is read, so no entry under any key can hand a book's picture to another source. */
    @Test
    fun `a local book's picture is refused to another source before its disk cache is read`() = runTest {
        val cache = DiskCache.Builder().directory(root.toOkioPath()).build()
        cache.openEditor(novelImageCacheKey(address, "lnreader.x"))!!.commitAndOpenSnapshot()!!.close()

        try {
            shouldThrow<FileNotFoundException> {
                fetchNovelImage(NovelImage(address, "lnreader.x"), mockk(), cache, readCache = true, writeCache = true)
                    .source.close()
            }
        } finally {
            cache.shutdown()
        }
    }

    /** The renderer reads Coil's memory cache before any fetch, so a picture decoded for the book must not answer there. */
    @Test
    fun `a local book's picture is cached apart from another source's chapter naming it`() {
        val keyer = NovelImageKeyer()

        keyer.key(NovelImage(address, "lnreader.x"), mockk()) shouldNotBe
            keyer.key(NovelImage(address, LocalNovelSource.ID), mockk())
    }
}
