package yokai.novel.source

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import yokai.novel.host.NovelItem
import yokai.novel.host.SourceNovel

class NovelSourceManagerTest {

    @Test
    fun `get returns null for unknown id`() {
        val mgr = NovelSourceManager()
        assertNull(mgr.get("nope"))
    }

    @Test
    fun `registered source is retrievable by id`() {
        val mgr = NovelSourceManager()
        val source = fakeSource("novelbin")
        mgr.register(source)
        assertEquals(source, mgr.get("novelbin"))
    }

    @Test
    fun `unregister removes the source`() {
        val mgr = NovelSourceManager()
        mgr.register(fakeSource("novelbin"))
        mgr.unregister("novelbin")
        assertNull(mgr.get("novelbin"))
    }

    @Test
    fun `double register replaces with the new instance`() {
        val mgr = NovelSourceManager()
        mgr.register(fakeSource("novelbin", name = "First"))
        mgr.register(fakeSource("novelbin", name = "Second"))
        assertEquals("Second", mgr.get("novelbin")!!.name)
    }

    @Test
    fun `sources flow emits the current set`() = runBlocking {
        val mgr = NovelSourceManager()
        mgr.register(fakeSource("a"))
        mgr.register(fakeSource("b"))
        val ids = mgr.sources.first().map { it.id }.toSet()
        assertEquals(setOf("a", "b"), ids)
    }

    @Test
    fun `getAll is empty before any register`() {
        assertTrue(NovelSourceManager().getAll().isEmpty())
    }

    @Test
    fun `getAll returns every registered source`() {
        val mgr = NovelSourceManager()
        mgr.register(fakeSource("a"))
        mgr.register(fakeSource("b"))
        assertEquals(setOf("a", "b"), mgr.getAll().map { it.id }.toSet())
    }

    private fun fakeSource(id: String, name: String = id): NovelSource = object : NovelSource {
        override val id = id
        override val name = name
        override val version = "1.0"
        override val site = "https://$id.example/"
        override val lang: String = ""
        override val iconUrl: String? = null
        override val filters: JsonObject? = null
        override suspend fun popularNovels(page: Int, optionsJson: String): List<NovelItem> = emptyList()
        override suspend fun searchNovels(query: String, page: Int): List<NovelItem> = emptyList()
        override suspend fun parseNovel(novelPath: String): SourceNovel = SourceNovel(path = novelPath)
        override suspend fun parseChapter(chapterPath: String): String = ""
    }
}
