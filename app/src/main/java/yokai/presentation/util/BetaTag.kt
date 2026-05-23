package yokai.presentation.util

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import dev.icerock.moko.resources.compose.stringResource
import yokai.i18n.MR

/**
 * Compose mirror of [eu.kanade.tachiyomi.util.lang.addBetaTag] (the Spanned/View-system variant).
 * Appends a styled "BETA" suffix to the receiver: bold + smaller + accent color + superscript
 * baseline. Returns an [AnnotatedString] so it flows through Compose's `Text(text = ...)` path.
 *
 * Composable so the accent color resolves against the active [MaterialTheme.colorScheme] at
 * render time, matching the legacy variant which reads `colorSecondary` from the View-system
 * theme attribute.
 *
 * The legacy variant uses a `scale(0.75f)` Spanned wrapper; Compose's [SpanStyle] doesn't take a
 * relative scale factor, so we set the BETA fontSize to 12sp absolute, which works out to ~0.75x
 * of the standard 16sp preference title size used by [yokai.presentation.component.preference.widget.BasePreferenceWidget].
 *
 * @param useSuperScript matches the legacy parameter; when false the BETA tag sits on the
 *   baseline instead of raised.
 */
@Composable
fun String.addBetaTag(useSuperScript: Boolean = true): AnnotatedString {
    val betaText = stringResource(MR.strings.beta)
    val accent = MaterialTheme.colorScheme.secondary
    return buildAnnotatedString {
        append(this@addBetaTag)
        withStyle(
            SpanStyle(
                color = accent,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                baselineShift = if (useSuperScript) BaselineShift.Superscript else BaselineShift.None,
            ),
        ) {
            append(betaText)
        }
    }
}
