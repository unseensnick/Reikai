package reikai.novel.content

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRenderingMode

/**
 * [TEXT_VIEW]: TextView renders scripts/styles as visible text, so they are always stripped.
 * [WEB_VIEW]: embedded CSS/JS can be preserved per user preferences.
 */
enum class RenderTarget { TEXT_VIEW, WEB_VIEW }

/** Every setting that changes what a chapter load produces, read in [from] and watched in [changes]. */
data class NovelContentConfig(
    val chapterUrl: String?,
    val chapterName: String,
    val target: RenderTarget,
    val hideTitle: Boolean = false,
    val forceLowercase: Boolean = false,
    val blockMedia: Boolean = false,
    val removeExtraSpacing: Boolean = false,
    val keepEmbeddedCss: Boolean = true,
    val keepEmbeddedJs: Boolean = false,
    val autoSplit: Boolean = false,
    val autoSplitWordCount: Int = 50,
    val regexRulesJson: String = "[]",
    /** Applied by the loader after the pipeline, so both readers draw the same escaped text. */
    val showRawHtml: Boolean = false,
) {
    companion object {
        fun from(
            preferences: NovelPreferences,
            chapterUrl: String?,
            chapterName: String,
        ): NovelContentConfig = NovelContentConfig(
            chapterUrl = chapterUrl,
            chapterName = chapterName,
            target = when (preferences.readerRenderingMode().get()) {
                NovelRenderingMode.NATIVE -> RenderTarget.TEXT_VIEW
                else -> RenderTarget.WEB_VIEW
            },
            hideTitle = preferences.readerHideChapterTitle().get(),
            forceLowercase = preferences.readerForceLowercase().get(),
            blockMedia = preferences.readerBlockMedia().get(),
            removeExtraSpacing = preferences.readerRemoveExtraSpacing().get(),
            keepEmbeddedCss = preferences.readerKeepEmbeddedCss().get(),
            keepEmbeddedJs = preferences.readerKeepEmbeddedJs().get(),
            autoSplit = preferences.readerAutoSplitText().get(),
            autoSplitWordCount = preferences.readerAutoSplitWordCount().get(),
            regexRulesJson = preferences.readerRegexReplacements().get(),
            showRawHtml = preferences.readerShowRawHtml().get(),
        )

        /**
         * Emits when [from] would produce a different config. Compared as a snapshot rather than by
         * counting emissions: `changes()` fires once on subscribe, and dropping a fixed number of those
         * breaks silently the day a setting is added.
         */
        fun changes(preferences: NovelPreferences): Flow<Unit> = inputs(preferences)
            .merge()
            .map { from(preferences, chapterUrl = null, chapterName = "") }
            .distinctUntilChanged()
            .drop(1)
            .map { }

        // Must name every preference [from] reads; NovelContentConfigTest compares the two.
        private fun inputs(preferences: NovelPreferences): List<Flow<Any?>> = listOf(
            preferences.readerRenderingMode().changes(),
            preferences.readerHideChapterTitle().changes(),
            preferences.readerForceLowercase().changes(),
            preferences.readerBlockMedia().changes(),
            preferences.readerRemoveExtraSpacing().changes(),
            preferences.readerKeepEmbeddedCss().changes(),
            preferences.readerKeepEmbeddedJs().changes(),
            preferences.readerAutoSplitText().changes(),
            preferences.readerAutoSplitWordCount().changes(),
            preferences.readerRegexReplacements().changes(),
            preferences.readerShowRawHtml().changes(),
        )
    }
}
