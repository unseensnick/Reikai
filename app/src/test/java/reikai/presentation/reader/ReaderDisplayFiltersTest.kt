package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.novel.NovelPreferences
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.core.common.preference.Preference

/**
 * Brightness and the colour treatment are each reader's own: one store holds both readers' keys, so a
 * change made in a novel session must not move the manga reader's page, or the reverse.
 */
class ReaderDisplayFiltersTest {

    private val store = EmittingPreferenceStore()
    private val manga = MangaReaderProvider(
        viewModel = mockk(relaxed = true),
        readerPreferences = ReaderPreferences(store),
        downloadManager = mockk(relaxed = true),
        titleWords = EnglishChapterTitleWords,
    ).displayFilters
    private val novel = NovelReaderProvider(
        viewModel = mockk(relaxed = true),
        novelPreferences = NovelPreferences(store),
        fontManager = mockk(),
        imageRequests = mockk(),
        titleWords = EnglishChapterTitleWords,
    ).displayFilters

    /** Every field of [ReaderDisplayFilters], with a value neither reader stores by default. */
    enum class Filter(val of: (ReaderDisplayFilters) -> Preference<*>, val changed: Any) {
        CUSTOM_BRIGHTNESS({ it.customBrightness }, true),
        CUSTOM_BRIGHTNESS_VALUE({ it.customBrightnessValue }, 40),
        COLOR_FILTER({ it.colorFilter }, true),
        COLOR_FILTER_VALUE({ it.colorFilterValue }, 0x55FF0000),
        COLOR_FILTER_MODE({ it.colorFilterMode }, 2),
        GRAYSCALE({ it.grayscale }, true),
        INVERTED_COLORS({ it.invertedColors }, true),
    }

    @Suppress("UNCHECKED_CAST")
    private fun Filter.set(filters: ReaderDisplayFilters) = (of(filters) as Preference<Any>).set(changed)

    private fun Filter.isDefault(filters: ReaderDisplayFilters) = of(filters).let { it.get() == it.defaultValue() }

    @ParameterizedTest
    @EnumSource(Filter::class)
    fun `a novel setting leaves manga's alone`(filter: Filter) {
        filter.set(novel)

        filter.isDefault(manga) shouldBe true
    }

    @ParameterizedTest
    @EnumSource(Filter::class)
    fun `a manga setting leaves the novel's alone`(filter: Filter) {
        filter.set(manga)

        filter.isDefault(novel) shouldBe true
    }

    /** So a filter added to the class cannot go unchecked by the two cases above. */
    @Test
    fun `every display filter is checked`() {
        val fields = ReaderDisplayFilters::class.java.declaredFields
        fields.count { Preference::class.java.isAssignableFrom(it.type) } shouldBe Filter.entries.size
    }

    /** The old novel sheet wrote these keys, and their values were kept for this step to read again. */
    @Test
    fun `the novel filter reads the colour the old novel reader stored`() {
        store.getInt("ln_reader_color_filter_value", 0).set(0x55FF0000)

        novel.colorFilterValue.get() shouldBe 0x55FF0000
    }
}
