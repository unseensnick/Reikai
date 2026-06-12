package reikai.novel.registry

import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LnRegistryTest {

    @Test
    fun `parses a single entry with all fields`() {
        val entries = LnRegistry.parse(
            """[{
                "id": "novelbin",
                "name": "Novel Bin",
                "site": "https://novelbin.com/",
                "lang": "English",
                "version": "2.2.1",
                "url": "https://example.com/novelbin.js",
                "iconUrl": "https://example.com/icon.png"
            }]""".trimIndent(),
        )
        val novelbin = LnRegistryEntry(
            id = "novelbin",
            name = "Novel Bin",
            version = "2.2.1",
            site = "https://novelbin.com/",
            lang = "English",
            url = "https://example.com/novelbin.js",
            iconUrl = "https://example.com/icon.png",
        )
        assertEquals(listOf(novelbin), entries)
    }

    @Test
    fun `optional fields default to null when absent`() {
        val entries = LnRegistry.parse(
            """[{
                "id": "minimal",
                "name": "Minimal",
                "site": "https://minimal.example/",
                "lang": "English",
                "version": "1.0",
                "url": "https://example.com/minimal.js"
            }]""".trimIndent(),
        )
        assertEquals(1, entries.size)
        val e = entries.first()
        assertNull(e.iconUrl)
        assertNull(e.customJS)
        assertNull(e.customCSS)
    }

    @Test
    fun `unknown top-level fields are tolerated`() {
        val entries = LnRegistry.parse(
            """[{
                "id": "future",
                "name": "Future Source",
                "site": "https://future.example/",
                "lang": "English",
                "version": "9.9.9",
                "url": "https://example.com/future.js",
                "experimental": true,
                "publishedAt": "2030-01-01",
                "deprecated": false
            }]""".trimIndent(),
        )
        assertEquals("future", entries.single().id)
    }

    @Test
    fun `parses an empty array`() {
        assertTrue(LnRegistry.parse("[]").isEmpty())
    }

    @Test
    fun `parses multiple entries preserving order`() {
        val entries = LnRegistry.parse(
            """[
              {"id":"a","name":"A","site":"https://a/","lang":"English","version":"1","url":"https://x/a.js"},
              {"id":"b","name":"B","site":"https://b/","lang":"English","version":"1","url":"https://x/b.js"},
              {"id":"c","name":"C","site":"https://c/","lang":"English","version":"1","url":"https://x/c.js"}
            ]""".trimIndent(),
        )
        assertEquals(listOf("a", "b", "c"), entries.map { it.id })
    }

    @Test
    fun `missing required field throws SerializationException`() {
        assertThrows(SerializationException::class.java) {
            LnRegistry.parse(
                """[{
                    "id": "broken",
                    "name": "Broken",
                    "site": "https://broken.example/",
                    "lang": "English",
                    "version": "1.0"
                }]""".trimIndent(),
            )
        }
    }

    @Test
    fun `malformed JSON throws SerializationException`() {
        assertThrows(SerializationException::class.java) {
            LnRegistry.parse("not-json")
        }
    }
}
