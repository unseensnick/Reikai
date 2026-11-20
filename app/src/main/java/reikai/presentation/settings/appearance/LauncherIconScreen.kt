package reikai.presentation.settings.appearance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import eu.kanade.presentation.components.AppBar
import kotlinx.serialization.Serializable
import mihon.app.di.appGraph
import mihon.core.navigation.LocalNavigator
import reikai.util.LauncherIcon
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/** Picks the icon the launcher shows, from the designs every build carries. */
// no ViewModel: the app-wide LauncherIconState holds what is shown.
@Serializable
data object LauncherIconRoute : NavKey

@Composable
fun LauncherIconScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val iconState = remember { context.appGraph.launcherIconState }
    val shown by iconState.shown.collectAsState()
    var asked by remember { mutableStateOf<LauncherIcon?>(null) }

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.pref_app_icon),
                navigateUp = navigator::pop,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            LauncherIcon.entries.groupBy { it.design }.forEach { (design, icons) ->
                HeadingItem(design.label)
                FlowRow(
                    modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium).selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    icons.forEach { icon ->
                        IconTile(icon, isShown = icon == shown) { if (icon != shown) asked = icon }
                    }
                }
            }
            Text(
                text = stringResource(MR.strings.app_icon_themed_hint),
                modifier = Modifier.padding(MaterialTheme.padding.medium),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    asked?.let { icon ->
        AlertDialog(
            onDismissRequest = { asked = null },
            title = { Text(stringResource(MR.strings.app_icon_change_title)) },
            text = { Text(stringResource(MR.strings.app_icon_change_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        asked = null
                        iconState.pick(icon)
                    },
                ) { Text(stringResource(MR.strings.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { asked = null }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
    }
}

@Composable
internal fun IconTile(icon: LauncherIcon, isShown: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .width(80.dp)
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = isShown, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = MaterialTheme.padding.small),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .border(
                    BorderStroke(3.dp, if (isShown) MaterialTheme.colorScheme.primary else Color.Transparent),
                    shape,
                )
                .padding(4.dp)
                .clip(shape),
        ) {
            // A launcher shows the middle 72 of the layers' 108 dp, so both layers are scaled to that view; the
            // Mihon style's disc is in its background.
            Image(
                painterResource(icon.background),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().scale(1.5f),
            )
            Image(
                painterResource(icon.foreground),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().scale(1.5f * icon.foregroundScale),
            )
        }
        Text(
            text = icon.scheme.label,
            modifier = Modifier.padding(top = MaterialTheme.padding.extraSmall),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
    }
}

private val LauncherIcon.Design.label
    @Composable get() = stringResource(
        when (this) {
            LauncherIcon.Design.CURRENT -> MR.strings.app_icon_original
            LauncherIcon.Design.ORIGINAL_RE -> MR.strings.app_icon_original_re
            LauncherIcon.Design.FLAME_R -> MR.strings.app_icon_flame_r
            LauncherIcon.Design.FLAME_RE -> MR.strings.app_icon_flame_re
            LauncherIcon.Design.MIHON_RE -> MR.strings.app_icon_mihon_re
        },
    )

private val LauncherIcon.Scheme.label
    @Composable get() = stringResource(
        when (this) {
            LauncherIcon.Scheme.STABLE -> MR.strings.app_icon_stable
            LauncherIcon.Scheme.NIGHTLY -> MR.strings.app_icon_nightly
            LauncherIcon.Scheme.DEV -> MR.strings.app_icon_dev
            LauncherIcon.Scheme.MIHON_LIGHT -> MR.strings.app_icon_mihon_light
            LauncherIcon.Scheme.MIHON_DARK -> MR.strings.app_icon_mihon_dark
            LauncherIcon.Scheme.OUTLINE -> MR.strings.app_icon_outline
        },
    )
