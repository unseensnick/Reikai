package reikai.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.novel.download.FakeSharedPreferences
import tachiyomi.core.common.preference.AndroidPreferenceStore

class StringListPreferenceTest {

    private val shared = FakeSharedPreferences()
    private val pref = AndroidPreferenceStore(shared).getStringList("list")

    @Test
    fun `a list reads back in the order it was written`() {
        pref.set(listOf("b", "a", "c"))

        pref.get() shouldBe listOf("b", "a", "c")
    }

    /** The format stored values and backups already hold, so the four keys read as they did. */
    @Test
    fun `a list is stored newline-joined`() {
        pref.set(listOf("b", "a", "c"))

        shared.getString("list", null) shouldBe "b\na\nc"
    }

    @Test
    fun `blank lines in a stored value are dropped`() {
        shared.edit().putString("list", "a\n\nb\n").apply()

        pref.get() shouldBe listOf("a", "b")
    }

    @Test
    fun `an unset key reads as an empty list`() {
        pref.get() shouldBe emptyList()
    }
}
