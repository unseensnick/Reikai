package reikai.source.local

import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Which of a volume folder's pictures its Extras chapter holds, as its listing and its reader both read it. */
@RunWith(RobolectricTestRunner::class)
class VolumeExtrasTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun extras(vararg names: String): List<String> {
        val volume = tmp.newFolder()
        names.forEach { File(volume, it).createNewFile() }
        return volumeExtras(volume.listFiles()!!.map { UniFile.fromFile(it)!! }).map { it.name.orEmpty() }.sorted()
    }

    @Test
    fun `every picture named as a cover is left out`() {
        extras("cover.jpg", "folder.jpg", "map.jpg") shouldBe listOf("map.jpg")
    }

    @Test
    fun `two cover-named pictures leave no extras`() {
        extras("cover.jpg", "folder.jpg") shouldBe emptyList()
    }

    @Test
    fun `an only picture is the cover`() {
        extras("art.jpg") shouldBe emptyList()
    }

    @Test
    fun `pictures with none named as a cover are all extras`() {
        extras("art.jpg", "map.jpg") shouldBe listOf("art.jpg", "map.jpg")
    }
}
