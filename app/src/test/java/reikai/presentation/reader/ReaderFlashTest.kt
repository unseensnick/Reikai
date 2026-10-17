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

/** The e-ink flash is each reader's own: a novel session changing it must not move the manga reader's. */
class ReaderFlashTest {

    private val store = EmittingPreferenceStore()
    private val manga = MangaReaderProvider(
        viewModel = mockk(relaxed = true),
        readerPreferences = ReaderPreferences(store),
        downloadManager = mockk(relaxed = true),
        titleWords = EnglishChapterTitleWords,
    ).flash
    private val novel = NovelReaderProvider(
        viewModel = mockk(relaxed = true),
        novelPreferences = NovelPreferences(store),
        fontManager = mockk(),
        imageRequests = mockk(),
        titleWords = EnglishChapterTitleWords,
    ).flash

    /** Every field of [ReaderFlash], with a value neither reader stores by default. */
    enum class Setting(val of: (ReaderFlash) -> Preference<*>, val changed: Any) {
        ON_PAGE_CHANGE({ it.onPageChange }, true),
        DURATION({ it.durationMillis }, 700),
        INTERVAL({ it.pageInterval }, 3),
        COLOR({ it.color }, ReaderPreferences.FlashColor.WHITE),
    }

    @Suppress("UNCHECKED_CAST")
    private fun Setting.set(flash: ReaderFlash) = (of(flash) as Preference<Any>).set(changed)

    private fun Setting.isDefault(flash: ReaderFlash) = of(flash).let { it.get() == it.defaultValue() }

    @ParameterizedTest
    @EnumSource(Setting::class)
    fun `a novel flash setting leaves manga's alone`(setting: Setting) {
        setting.set(novel)

        setting.isDefault(manga) shouldBe true
    }

    @ParameterizedTest
    @EnumSource(Setting::class)
    fun `a manga flash setting leaves the novel's alone`(setting: Setting) {
        setting.set(manga)

        setting.isDefault(novel) shouldBe true
    }

    /** So a setting added to the class cannot go unchecked by the two cases above. */
    @Test
    fun `every flash setting is checked`() {
        val fields = ReaderFlash::class.java.declaredFields
        fields.count { Preference::class.java.isAssignableFrom(it.type) } shouldBe Setting.entries.size
    }
}
