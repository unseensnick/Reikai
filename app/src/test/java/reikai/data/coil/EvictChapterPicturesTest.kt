package reikai.data.coil

import android.graphics.Canvas
import coil3.Image
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import io.kotest.matchers.shouldBe
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reikai.novel.local.LocalNovelSource
import java.io.File

/** A reload from the source drops what the chapter's pictures left in coil's caches, and nothing else. */
class EvictChapterPicturesTest {

    @TempDir
    lateinit var root: File

    private lateinit var disk: DiskCache
    private val memory = MemoryCache.Builder().maxSizeBytes(1_000_000).build()

    @BeforeEach
    fun setUp() {
        disk = DiskCache.Builder().directory(root.toOkioPath()).build()
    }

    @AfterEach
    fun tearDown() = disk.shutdown()

    private fun cached(url: String) {
        disk.openEditor(url)!!.commitAndOpenSnapshot()!!.close()
        memory[MemoryCache.Key(url, mapOf("coil#size" to "100x100"))] = MemoryCache.Value(StandIn)
    }

    private fun inDisk(url: String) = disk.openSnapshot(url)?.use { true } ?: false

    private fun inMemory(url: String) = memory.keys.any { it.key == url }

    private val html = """
        <p><img src="plate.png"></p>
        <img srcset="https://b.test/small.png 1x, https://b.test/big.png 2x">
    """.trimIndent()

    private val pictures = listOf(
        "https://a.test/chapter/plate.png",
        "https://b.test/small.png",
        "https://b.test/big.png",
    )

    @Test
    fun `every picture the chapter names leaves the disk cache`() {
        (pictures + "https://c.test/other.png").forEach(::cached)

        evictChapterPictures(html, "https://a.test/chapter/1", "src", memory, disk)

        (pictures + "https://c.test/other.png").map(::inDisk) shouldBe listOf(false, false, false, true)
    }

    @Test
    fun `every picture the chapter names leaves the memory cache`() {
        (pictures + "https://c.test/other.png").forEach(::cached)

        evictChapterPictures(html, "https://a.test/chapter/1", "src", memory, disk)

        (pictures + "https://c.test/other.png").map(::inMemory) shouldBe listOf(false, false, false, true)
    }

    /** A local book's picture is cached under its source too, so the eviction has to name the same source. */
    @Test
    fun `a local book's picture the chapter names leaves the disk cache`() {
        val picture = LocalNovelSource.pictureAddress("Saga", "Book.epub", "OEBPS/plate.png", 1)
        val key = novelImageCacheKey(picture, LocalNovelSource.ID)
        disk.openEditor(key)!!.commitAndOpenSnapshot()!!.close()

        evictChapterPictures("""<img src="$picture">""", null, LocalNovelSource.ID, memory, disk)

        inDisk(key) shouldBe false
    }

    private object StandIn : Image {
        override val size = 1L
        override val width = 1
        override val height = 1
        override val shareable = true
        override fun draw(canvas: Canvas) = Unit
    }
}
