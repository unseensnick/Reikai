package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelPreferences
import reikai.presentation.recents.EmittingPreferenceStore

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
        titleWords = EnglishChapterTitleWords,
    ).displayFilters

    @Test
    fun `grayscale turned on for novels leaves manga's page alone`() {
        novel.grayscale.set(true)

        manga.grayscale.get() shouldBe false
    }

    @Test
    fun `inverted colours turned on for manga leave the novel page alone`() {
        manga.invertedColors.set(true)

        novel.invertedColors.get() shouldBe false
    }

    @Test
    fun `a novel brightness leaves manga's brightness alone`() {
        novel.customBrightnessValue.set(40)

        manga.customBrightnessValue.get() shouldBe 0
    }

    @Test
    fun `a manga colour filter leaves the novel filter alone`() {
        manga.colorFilter.set(true)

        novel.colorFilter.get() shouldBe false
    }

    /** The old novel sheet wrote these keys, and their values were kept for this step to read again. */
    @Test
    fun `the novel filter reads the colour the old novel reader stored`() {
        store.getInt("ln_reader_color_filter_value", 0).set(0x55FF0000)

        novel.colorFilterValue.get() shouldBe 0x55FF0000
    }
}
