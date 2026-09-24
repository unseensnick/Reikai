package reikai.novel.content

import androidx.annotation.WorkerThread
import reikai.domain.novel.NovelPreferences

/**
 * Stage order is user-visible and fixed: strip title, normalize, remove extra spacing, regex
 * replacements, lowercase, auto-split, sanitize. Each stage sees what the previous one produced (a
 * regex rule matches post-normalization markup, auto-split counts words after those rules ran), so
 * reordering changes the rendered output for some chapters.
 */
class NovelContentPipeline(private val preferences: NovelPreferences) {

    @WorkerThread
    fun process(raw: String, config: NovelContentConfig): NovelChapterContent {
        var content = raw
        val plainTextMode = NovelHtmlUtils.isPlainTextChapter(config.chapterUrl)

        if (config.hideTitle) {
            content = NovelHtmlUtils.stripChapterTitle(content, config.chapterName)
        }

        content = if (plainTextMode) {
            NovelHtmlUtils.normalizePlainTextContent(content)
        } else {
            NovelHtmlUtils.normalizeContentForHtml(content, config.chapterUrl)
        }

        // Before the user's own rules, so a rule matches the markup they can see rather than the
        // padding a source happened to ship.
        if (config.removeExtraSpacing && !plainTextMode) {
            content = NovelHtmlUtils.removeExtraParagraphSpacing(content)
        }

        content = NovelRegexReplacements.apply(content, preferences)

        if (config.forceLowercase) {
            content = if (plainTextMode) content.lowercase() else NovelHtmlUtils.lowercaseText(content)
        }

        if (preferences.readerAutoSplitText().get()) {
            content = NovelTextSplitter.splitText(
                text = content,
                wordCount = preferences.readerAutoSplitWordCount().get(),
                isHtml = !plainTextMode,
            )
        }

        if (!plainTextMode) {
            // Before the sanitiser, which must see the markup it lets through as the final markup.
            content = NovelHtmlUtils.wrapBareParagraphs(content)
            content = NovelHtmlUtils.sanitizeForRender(
                content,
                target = config.target,
                keepEmbeddedCss = config.keepEmbeddedCss,
                keepEmbeddedJs = config.keepEmbeddedJs,
                blockMedia = config.blockMedia,
            )
        }

        return NovelChapterContent(text = content, isPlainText = plainTextMode)
    }
}
