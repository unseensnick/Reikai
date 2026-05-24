package yokai.presentation.library.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.preference.toggle
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.openInBrowser
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.i18n.MR

/**
 * Compose port of the legacy [eu.kanade.tachiyomi.ui.more.OverflowDialog]: a rounded card
 * anchored under the toolbar with five entries (incognito toggle, Settings, Stats, About,
 * Help). Outside-tap dismisses. The icon swap on Incognito is rendered with [Crossfade] in
 * place of the legacy AnimatedVectorDrawable to keep the Compose surface free of vector-
 * drawable plumbing while preserving the visual cue.
 *
 * Caller passes the anchor-relative state ([expanded] / [onDismiss]). The composable owns
 * incognito state internally so the row label and icon update on tap without leaving the menu.
 */
@Composable
fun LibraryOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
) {
    if (!expanded) return

    val context = LocalContext.current
    val activity = context as? MainActivity
    val preferences: PreferencesHelper = remember { Injekt.get() }

    // Read once on open, update locally after each toggle so the label / icon flip immediately
    // without round-tripping through a flow collection.
    var incognito by remember { mutableStateOf(preferences.incognitoMode().get()) }

    val tint = MaterialTheme.colorScheme.secondary
        .copy(alpha = 0.075f)
        .compositeOver(MaterialTheme.colorScheme.background)

    Popup(
        alignment = Alignment.TopEnd,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
        offset = androidx.compose.ui.unit.IntOffset(0, 0),
    ) {
        Surface(
            color = tint,
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 4.dp,
            shadowElevation = 4.dp,
            modifier = Modifier
                .padding(end = 8.dp, top = 8.dp)
                .width(280.dp),
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                IncognitoRow(
                    enabled = incognito,
                    onClick = {
                        preferences.incognitoMode().toggle()
                        incognito = preferences.incognitoMode().get()
                    },
                )
                OverflowRow(
                    iconRes = R.drawable.ic_outline_settings_24dp,
                    title = stringResource(MR.strings.settings),
                    onClick = {
                        activity?.showSettings()
                        onDismiss()
                    },
                )
                OverflowRow(
                    iconRes = R.drawable.ic_query_stats_24dp,
                    title = stringResource(MR.strings.statistics),
                    onClick = {
                        activity?.showStats()
                        onDismiss()
                    },
                )
                OverflowRow(
                    iconRes = R.drawable.ic_info_outline_24dp,
                    title = stringResource(MR.strings.about),
                    subtitle = aboutVersionSubtitle(),
                    onClick = {
                        activity?.showAbout()
                        onDismiss()
                    },
                )
                OverflowRow(
                    iconRes = R.drawable.ic_help_outline_24dp,
                    title = stringResource(MR.strings.help),
                    onClick = {
                        context.openInBrowser(URL_HELP)
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun IncognitoRow(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val incog = stringResource(MR.strings.incognito_mode)
    val title = stringResource(
        if (enabled) MR.strings.turn_off_ else MR.strings.turn_on_,
        incog,
    )
    val subtitle = stringResource(MR.strings.pauses_reading_history)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(24.dp)) {
            Crossfade(targetState = enabled, label = "incognito-icon") { on ->
                Icon(
                    painter = painterResource(
                        if (on) R.drawable.ic_incognito_24dp else R.drawable.ic_glasses_24dp,
                    ),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OverflowRow(
    iconRes: Int,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun aboutVersionSubtitle(): String {
    val version = remember { "v${BuildConfig.VERSION_NAME}".substringBefore("-") }
    return if (BuildConfig.BETA) "$version (Beta)" else version
}

// Same value as OverflowDialog.URL_HELP. Re-declared so we do not have to expose the legacy
// private companion (the value is stable and the legacy file is in the "not touched at any
// phase" list).
private const val URL_HELP = "https://tachiyomi.org/docs/guides/troubleshooting/"
