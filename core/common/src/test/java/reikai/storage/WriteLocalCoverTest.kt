package reikai.storage

import android.content.ContentResolver
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import com.hippo.unifile.UniFile
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/** A local series' cover, as both local sources write it. */
class WriteLocalCoverTest {

    private val written = ByteArrayOutputStream()
    private val created = mutableListOf<String>()
    private val coverUri = mockk<Uri> { every { path } returns "/series/cover.jpg" }

    // A cover opened for writing any other way than from empty ("wt") is not stubbed, so it fails the write.
    private val cover = mockk<UniFile> { every { uri } returns coverUri }
    private val context = mockk<Context> {
        every { contentResolver } returns mockk<ContentResolver> {
            every { openOutputStream(coverUri, "wt") } returns written
        }
    }
    private val folder = mockk<UniFile> {
        every { listFiles() } returns emptyArray()
        every { exists() } returns true
        every { uri } returns coverUri
        every { findFile(any()) } returns null
        every { createFile(any()) } answers {
            created += firstArg<String>()
            cover
        }
    }

    @BeforeEach
    fun setUp() {
        mockkStatic(MediaScannerConnection::class)
        every { MediaScannerConnection.scanFile(any(), any(), any(), any()) } just runs
    }

    @AfterEach
    fun tearDown() = unmockkStatic(MediaScannerConnection::class)

    /** A document provider takes plain "w" as write in place, which leaves a bigger old cover's tail. */
    @Test
    fun `a cover is written from empty`() {
        writeLocalCover(context, folder, byteArrayOf(1, 2, 3).inputStream())

        written.toByteArray().toList() shouldBe listOf<Byte>(1, 2, 3)
    }

    @Test
    fun `the series folder is kept out of the gallery`() {
        writeLocalCover(context, folder, byteArrayOf(1).inputStream())

        created shouldContain ".nomedia"
    }
}
