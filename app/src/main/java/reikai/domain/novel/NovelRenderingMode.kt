package reikai.domain.novel

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * Which renderer a novel opens in. Persisted by name through `getEnum`, so the constant names are
 * load-bearing; an unknown one falls back to the default, which is how a stored `LEGACY` from the
 * retired standalone reader lands on [NATIVE] without a migration.
 */
enum class NovelRenderingMode(val titleRes: StringResource) {
    /** The shared host with the native text renderer. Listed first, as the default. */
    NATIVE(MR.strings.pref_novel_rendering_mode_native),

    /** The shared reader host rendering Reikai's own web document. */
    WEBVIEW(MR.strings.pref_novel_rendering_mode_webview),
}
