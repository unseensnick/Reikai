package reikai.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.Robolectric
import reikai.domain.library.ContentType
import reikai.util.resetVirtualDispatchers
import reikai.util.restubDefaultDispatcher
import reikai.util.virtualDispatchers
import java.io.File

/** An import into a series already stored reads the series' details again, through the real engine on both types. */
@RunWith(ParameterizedRobolectricTestRunner::class)
class LocalImportRefreshConformanceTest(private val type: ContentType) {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var world: CombineWorld

    @Before
    fun setUp() {
        virtualDispatchers()
        Robolectric.setupContentProvider(ImportDocuments::class.java, ImportDocuments.AUTHORITY)
        world = if (type == ContentType.NOVELS) NovelCombineWorld(context) else MangaCombineWorld(context)
        runTest { world.open(tmp, mapOf(ID to "Kept")) }
        restubDefaultDispatcher()
    }

    @After
    fun tearDown() {
        runTest {
            ImportDocuments.documents.clear()
            world.close()
        }
        resetVirtualDispatchers()
    }

    @Test
    fun `an import into a stored series reads its details again`() = runTest {
        File(world.folderOf("Kept"), "details.json").writeText("""{"title":"Kept","description":"Fresh"}""")
        val name = if (type == ContentType.NOVELS) "two.epub" else "002.jpg"
        ImportDocuments.documents["1"] = ImportDocuments.Document(name, 3L, null, "abc".toByteArray())
        val group = ImportGroup(
            key = "1",
            type = type,
            title = "Kept",
            author = null,
            cover = null,
            files = listOf(ImportFile(ImportDocuments.uri("1"), name, 3L)),
            canBeEitherType = false,
            existing = "Kept",
        )

        world.onApp { world.import.import(listOf(group), addToLibrary = false, categoryIds = emptyList()) }

        world.description(ID) shouldBe "Fresh"
    }

    companion object {
        private const val ID = 1L

        @JvmStatic
        @Parameters(name = "{0}")
        fun types() = listOf(ContentType.MANGA, ContentType.NOVELS).map { arrayOf<Any>(it) }
    }
}
