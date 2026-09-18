package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.novel.NovelPreferences
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.core.common.preference.Preference

/**
 * Fullscreen and draw-under-cutout are each reader's own, and the host applies whichever pair the
 * open session hands over, so turning one off for novels must not change the manga window.
 */
class ReaderWindowSettingsTest {

    private val store = EmittingPreferenceStore()
    private val manga = MangaReaderProvider(
        viewModel = mockk(relaxed = true),
        readerPreferences = ReaderPreferences(store),
        downloadManager = mockk(relaxed = true),
        titleWords = EnglishChapterTitleWords,
    )
    private val novel = NovelReaderProvider(
        viewModel = mockk(relaxed = true),
        novelPreferences = NovelPreferences(store),
        fontManager = mockk(),
        titleWords = EnglishChapterTitleWords,
    )

    enum class Setting(val of: (ReaderProvider) -> Preference<Boolean>) {
        FULLSCREEN({ it.fullscreen }),
        DRAW_UNDER_CUTOUT({ it.drawUnderCutout }),
    }

    @ParameterizedTest
    @EnumSource(Setting::class)
    fun `a novel window setting leaves manga's alone`(setting: Setting) {
        setting.of(novel).set(!setting.of(novel).defaultValue())

        setting.of(manga).isSet() shouldBe false
    }

    @ParameterizedTest
    @EnumSource(Setting::class)
    fun `a manga window setting leaves the novel's alone`(setting: Setting) {
        setting.of(manga).set(!setting.of(manga).defaultValue())

        setting.of(novel).isSet() shouldBe false
    }
}
