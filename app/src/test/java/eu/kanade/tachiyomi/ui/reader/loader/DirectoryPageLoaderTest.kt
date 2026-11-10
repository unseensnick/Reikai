package eu.kanade.tachiyomi.ui.reader.loader

import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.source.local.isVolumeCover
import java.io.File

/** A local volume's Extras chapter opens the volume folder, whose cover is the volume's, not a page. */
@RunWith(RobolectricTestRunner::class)
class DirectoryPageLoaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `an Extras chapter leaves out its volume's cover`() = runTest {
        val volume = tmp.newFolder("Vol 1")
        listOf("cover.jpg", "p1.jpg", "p2.jpg").forEach { File(volume, it).writeBytes(byteArrayOf(1)) }

        DirectoryPageLoader(UniFile.fromFile(volume)!!, skips = ::isVolumeCover).getPages().size shouldBe 2
    }
}
