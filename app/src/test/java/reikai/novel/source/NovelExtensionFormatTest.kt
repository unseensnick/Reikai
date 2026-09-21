package reikai.novel.source

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** A list names each novel row's format only when that tells the rows apart, on both Browse tabs. */
class NovelExtensionFormatTest {

    @Test
    fun `plugins beside apps are told apart`() {
        NovelExtensionFormat.tellsApart(listOf(NovelExtensionFormat.JS, null, NovelExtensionFormat.APK)) shouldBe true
    }

    @Test
    fun `one format among manga rows needs no telling apart`() {
        NovelExtensionFormat.tellsApart(listOf(NovelExtensionFormat.JS, null, NovelExtensionFormat.JS)) shouldBe false
    }
}
