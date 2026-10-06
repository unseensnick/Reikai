package reikai.domain.novel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences

/** A fresh install schedules and filters the novel update the way Mihon's defaults do for manga. */
class NovelUpdateDefaultsTest {

    private val store = InMemoryPreferenceStore()
    private val novel = NovelPreferences(store)
    private val manga = LibraryPreferences(store)

    @Test
    fun `the update interval defaults to manga's`() {
        novel.libraryUpdateInterval().defaultValue() shouldBe manga.autoUpdateInterval.defaultValue()
    }

    @Test
    fun `the device restrictions default to manga's`() {
        novel.libraryUpdateDeviceRestrictions().defaultValue() shouldBe
            manga.autoUpdateDeviceRestrictions.defaultValue()
    }

    @Test
    fun `the smart update restrictions default to manga's`() {
        novel.novelUpdateRestrictions().defaultValue() shouldBe manga.autoUpdateMangaRestrictions.defaultValue()
    }
}
