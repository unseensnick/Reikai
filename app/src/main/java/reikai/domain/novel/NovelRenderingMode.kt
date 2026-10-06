package reikai.domain.novel

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * Which renderer a novel opens in. Persisted by name through `getEnum`, so the constant names are
 * load-bearing; an unknown one falls back to the default, which is how a stored `LEGACY` from the
 * retired standalone reader lands on [NATIVE] without a migration.
 *
 * [rendersMarkup] is whether the chapter's own stylesheet, scripts and fonts reach the page, so every
 * setting that keeps or adds to them shows only for such a mode, on the settings screen and the sheet.
 */
enum class NovelRenderingMode(val titleRes: StringResource, val rendersMarkup: Boolean) {
    /** The shared host with the native text renderer. Listed first, as the default. */
    NATIVE(MR.strings.pref_novel_rendering_mode_native, rendersMarkup = false),

    /** The shared reader host rendering Reikai's own web document. */
    WEBVIEW(MR.strings.pref_novel_rendering_mode_webview, rendersMarkup = true),
}
