package eu.kanade.presentation.browse

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.browse.components.BaseBrowseItem
import eu.kanade.presentation.browse.components.ExtensionIcon
import eu.kanade.presentation.browse.components.ExtensionPill
import eu.kanade.presentation.browse.components.label
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionUiModel
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.copyToClipboard
import mihon.domain.extension.model.ExtensionStore
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.ExpandMore
import mihon.icons.materialsymbols.rounded.Info
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Settings
import mihon.icons.materialsymbols.rounded.VerifiedUser
import reikai.presentation.browse.components.NovelIconInset
import reikai.presentation.browse.extension.versionLabel
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

// RK: partially collapsed. The screen, its sectioned list, pull-to-refresh, the install-permission
//     banner and the loading and empty states moved to the shared Extensions engine and tab
//     (reikai/presentation/browse/extension/). What is left is the apk extension row and the
//     trust, not-loaded, install-error and uninstall dialogs it raises, which the shared list draws
//     for its apk half, plus the NotLoadedDialog body that the novel plugin rows share.

// RK: public so the Reikai unified Browse view can host it; the plugin rows raise it too.
@Composable
fun ExtensionInstallErrorDialog(
    error: InstallStep.Error,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        title = {
            Text(text = stringResource(MR.strings.ext_install_error))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                Text(
                    text = error.message,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                error.stackTrace?.let { stackTrace ->
                    val context = LocalContext.current
                    TextButton(
                        onClick = {
                            context.copyToClipboard(
                                label = context.stringResource(MR.strings.ext_copy_stacktrace),
                                content = stackTrace,
                            )
                        },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(text = stringResource(MR.strings.ext_copy_stacktrace))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        onDismissRequest = onDismissRequest,
    )
}

// RK: public so the Reikai unified Browse view can reuse the apk extension row verbatim.
@Composable
fun ExtensionItem(
    item: ExtensionUiModel.Item,
    onClickItem: (Extension) -> Unit,
    onLongClickItem: (Extension) -> Unit,
    onClickItemCancel: (Extension) -> Unit,
    onClickItemError: (InstallStep.Error) -> Unit,
    onClickItemAction: (Extension) -> Unit,
    onClickItemSecondaryAction: (Extension) -> Unit,
    modifier: Modifier = Modifier,
    // RK: content-type badge, beside the name, drawn by the shared list when it holds both types.
    badge: @Composable () -> Unit = {},
    // RK: the language outside a language's own section, and the version a pending update brings
    showsLanguage: Boolean,
    updateVersion: String?,
) {
    val (extension, installStep) = item
    val store = when (extension) {
        is Extension.Available -> extension.store
        is Extension.Installed -> extension.store
    }

    BaseBrowseItem(
        modifier = modifier,
        onClickItem = { onClickItem(extension) },
        onLongClickItem = { onLongClickItem(extension) },
        icon = {
            ExtensionItemIcon(extension = extension, installStep = installStep)
        },
        action = {
            ExtensionItemActions(
                extension = extension,
                installStep = installStep,
                onClickItemCancel = onClickItemCancel,
                onClickItemError = onClickItemError,
                onClickItemAction = onClickItemAction,
                onClickItemSecondaryAction = onClickItemSecondaryAction,
            )
        },
    ) {
        ExtensionItemContent(
            extension = extension,
            installStep = installStep,
            store = store,
            // RK: the row's own parameters, see ExtensionItem
            badge = badge,
            showsLanguage = showsLanguage,
            updateVersion = updateVersion,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ExtensionItemContent(
    extension: Extension,
    installStep: InstallStep,
    store: ExtensionStore?,
    modifier: Modifier = Modifier,
    // RK: content-type badge, beside the name.
    badge: @Composable () -> Unit = {},
    showsLanguage: Boolean,
    updateVersion: String?,
) {
    Column(
        modifier = modifier.padding(start = MaterialTheme.padding.medium),
    ) {
        // RK --> the name shares a row with the content-type badge
        Row(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = extension.name,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            badge()
        }
        // RK <--

        if (store != null) {
            Text(
                text = store.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ExtensionItemMetadata(
            extension = extension,
            installStep = installStep,
            // RK: the row's own parameters, see ExtensionItem
            showsLanguage = showsLanguage,
            updateVersion = updateVersion,
        )
    }
}

@Composable
private fun ExtensionItemIcon(
    extension: Extension,
    installStep: InstallStep,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.size(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        val idle = installStep.isCompleted()
        if (!idle) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                strokeWidth = 2.dp,
            )
        }

        val padding by animateDpAsState(
            targetValue = if (idle) 0.dp else 8.dp,
            label = "iconPadding",
        )
        ExtensionIcon(
            extension = extension,
            modifier = Modifier
                .matchParentSize()
                .padding(padding)
                // RK: a novel extension's full-bleed icon, inset as the novel source rows do
                .padding(if (extension.kind == Extension.Kind.MANGA) 0.dp else NovelIconInset),
        )
    }
}

/**
 * Plain text for what an extension simply is, pills for the few things worth noticing about it.
 * Won't look good when it wraps, but it's not like overflowing content can be ellipsized.
 */
@Composable
private fun ExtensionItemMetadata(
    extension: Extension,
    installStep: InstallStep,
    modifier: Modifier = Modifier,
    // RK: see ExtensionItem
    showsLanguage: Boolean,
    updateVersion: String?,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall / 2),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        val facts = listOfNotNull(
            // RK: shown wherever the list asks, so an extension that did not load names it too
            extension.lang
                ?.takeIf { showsLanguage && it.isNotEmpty() }
                ?.let { LocaleHelper.getSourceDisplayName(it, LocalContext.current) },
            // RK: with the version a pending update brings
            extension.versionName.takeIf { it.isNotEmpty() }?.let { versionLabel(it, updateVersion) },
        )
        if (facts.isNotEmpty()) {
            Text(
                text = facts.joinToString(" • "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val notable = listOfNotNull(
            when (installStep) {
                InstallStep.Pending -> MR.strings.ext_pending
                InstallStep.Downloading -> MR.strings.ext_downloading
                InstallStep.Installing -> MR.strings.ext_installing
                else -> null
            }?.let { it to MaterialTheme.colorScheme.primary },
            when {
                extension is Extension.NotLoaded ->
                    extension.reason.labelRes?.let { it to MaterialTheme.colorScheme.error }
                extension is Extension.Loaded && extension.isObsolete ->
                    MR.strings.ext_obsolete to MaterialTheme.colorScheme.error
                else -> null
            },
            extension.contentWarning.label?.let { it.title to it.color },
            (extension as? Extension.Loaded)
                ?.takeIf { !it.isShared }
                ?.let { MR.strings.ext_installer_private to MaterialTheme.colorScheme.onSurfaceVariant },
        )
        notable.forEach { (label, color) ->
            ExtensionPill(text = stringResource(label), color = color)
        }
    }
}

@Composable
private fun ExtensionItemActions(
    extension: Extension,
    installStep: InstallStep,
    modifier: Modifier = Modifier,
    onClickItemCancel: (Extension) -> Unit = {},
    onClickItemError: (InstallStep.Error) -> Unit = {},
    onClickItemAction: (Extension) -> Unit = {},
    onClickItemSecondaryAction: (Extension) -> Unit = {},
) {
    val isUntrusted = (extension as? Extension.NotLoaded)?.reason is Extension.NotLoaded.Reason.Untrusted
    val secondaryAction = when (extension) {
        // RK: an index that names no site (an old-style or IReader one) has nothing to open
        is Extension.Available -> extension.sources.firstOrNull()?.baseUrl?.takeIf { it.isNotEmpty() }
            ?.let { stringResource(MR.strings.action_open_in_web_view) }
        is Extension.Loaded -> stringResource(MR.strings.action_settings)
        is Extension.NotLoaded -> if (isUntrusted) {
            stringResource(MR.strings.ext_trust)
        } else {
            stringResource(MR.strings.ext_not_loaded_details)
        }
    }
        ?.let { it to { onClickItemSecondaryAction(extension) } }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            !installStep.isCompleted() -> {
                IconButton(onClick = { onClickItemCancel(extension) }) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Close,
                        contentDescription = stringResource(MR.strings.action_cancel),
                    )
                }
            }
            installStep is InstallStep.Error -> {
                ExtensionSplitButton(
                    icon = MaterialSymbols.Rounded.Refresh,
                    contentDescription = stringResource(MR.strings.action_retry),
                    onClick = { onClickItemAction(extension) },
                    menuItems = listOfNotNull(
                        stringResource(MR.strings.ext_install_error_details) to { onClickItemError(installStep) },
                        secondaryAction,
                    ),
                )
            }
            extension is Extension.Available -> {
                ExtensionSplitButton(
                    icon = MaterialSymbols.Rounded.Download,
                    contentDescription = stringResource(MR.strings.ext_install),
                    onClick = { onClickItemAction(extension) },
                    menuItems = listOfNotNull(secondaryAction),
                )
            }
            extension is Extension.Installed && extension.hasUpdate -> {
                ExtensionSplitButton(
                    icon = MaterialSymbols.Rounded.Download,
                    contentDescription = stringResource(MR.strings.ext_update),
                    onClick = { onClickItemAction(extension) },
                    menuItems = listOfNotNull(secondaryAction),
                )
            }
            extension is Extension.Loaded -> {
                FilledTonalIconButton(onClick = { onClickItemSecondaryAction(extension) }) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Settings,
                        contentDescription = stringResource(MR.strings.action_settings),
                    )
                }
            }
            extension is Extension.NotLoaded -> {
                IconButton(onClick = { onClickItemSecondaryAction(extension) }) {
                    Icon(
                        imageVector = if (isUntrusted) {
                            MaterialSymbols.Rounded.VerifiedUser
                        } else {
                            MaterialSymbols.Rounded.Info
                        },
                        contentDescription = secondaryAction?.first,
                    )
                }
            }
        }
    }
}

// RK: public so the novel plugin rows take the same Install, Update and Retry buttons.
@Composable
fun ExtensionSplitButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    menuItems: List<Pair<String, () -> Unit>>,
) {
    if (menuItems.isEmpty()) {
        FilledTonalIconButton(onClick = onClick) {
            Icon(imageVector = icon, contentDescription = contentDescription)
        }
        return
    }

    val size = SplitButtonDefaults.ExtraSmallContainerHeight
    var expanded by remember { mutableStateOf(false) }
    SplitButtonLayout(
        leadingButton = {
            SplitButtonDefaults.TonalLeadingButton(
                onClick = onClick,
                modifier = Modifier.height(size),
                shapes = SplitButtonDefaults.leadingButtonShapesFor(size),
                contentPadding = SplitButtonDefaults.leadingButtonContentPaddingFor(size),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(SplitButtonDefaults.leadingButtonIconSizeFor(size)),
                )
            }
        },
        trailingButton = {
            Box {
                SplitButtonDefaults.TonalTrailingButton(
                    checked = expanded,
                    onCheckedChange = { expanded = it },
                    modifier = Modifier.height(size),
                    shapes = SplitButtonDefaults.trailingButtonShapesFor(size),
                    contentPadding = SplitButtonDefaults.trailingButtonContentPaddingFor(size),
                ) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.ExpandMore,
                        contentDescription = stringResource(MR.strings.action_menu),
                        modifier = Modifier.size(SplitButtonDefaults.trailingButtonIconSizeFor(size)),
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    offset = DpOffset.Zero,
                ) {
                    menuItems.forEach { (text, action) ->
                        DropdownMenuItem(
                            text = { Text(text = text) },
                            onClick = {
                                expanded = false
                                action()
                            },
                        )
                    }
                }
            }
        },
    )
}

/**
 * Only the reasons the user can act on are worth naming in the row; the rest all mean "broken" to
 * them and are spelled out in [ExtensionNotLoadedDialog] instead.
 */
private val Extension.NotLoaded.Reason.labelRes: StringResource?
    get() = when (this) {
        is Extension.NotLoaded.Reason.Untrusted -> MR.strings.ext_untrusted
        Extension.NotLoaded.Reason.Filtered -> MR.strings.ext_filtered
        // The section header already says these aren't loaded; the dialog says why
        Extension.NotLoaded.Reason.Unsigned,
        Extension.NotLoaded.Reason.UnsupportedLibVersion,
        Extension.NotLoaded.Reason.Malformed,
        is Extension.NotLoaded.Reason.Failed,
        -> null
    }

private val Extension.NotLoaded.Reason.descriptionRes: StringResource
    get() = when (this) {
        is Extension.NotLoaded.Reason.Untrusted -> MR.strings.untrusted_extension_message
        Extension.NotLoaded.Reason.Filtered -> MR.strings.ext_filtered_message
        Extension.NotLoaded.Reason.Unsigned -> MR.strings.ext_unsigned_message
        Extension.NotLoaded.Reason.UnsupportedLibVersion -> MR.strings.ext_unsupported_message
        Extension.NotLoaded.Reason.Malformed -> MR.strings.ext_malformed_message
        is Extension.NotLoaded.Reason.Failed -> MR.strings.ext_load_failed_message
    }

// RK: public so the Reikai unified Browse view can host it, over the body novel plugins share.
@Composable
fun ExtensionNotLoadedDialog(
    reason: Extension.NotLoaded.Reason,
    onClickUninstall: () -> Unit,
    onDismissRequest: () -> Unit,
) { // RK: the body is the shared NotLoadedDialog below
    val failure = reason as? Extension.NotLoaded.Reason.Failed
    NotLoadedDialog(
        description = reason.descriptionRes,
        failureMessage = failure?.message,
        stackTrace = failure?.stackTrace,
        onClickUninstall = onClickUninstall,
        onDismissRequest = onDismissRequest,
    )
}

// RK --> upstream's dialog body, typed on what it shows rather than on a manga extension's reason, so
// a novel plugin that failed to load explains itself with the same dialog.
@Composable
fun NotLoadedDialog(
    description: StringResource,
    failureMessage: String?,
    stackTrace: String?,
    onClickUninstall: () -> Unit,
    onDismissRequest: () -> Unit,
    confirmLabel: StringResource = MR.strings.action_ok,
    onClickConfirm: () -> Unit = onDismissRequest,
) {
    AlertDialog(
        title = {
            Text(text = stringResource(MR.strings.ext_not_loaded_dialog))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                Text(text = stringResource(description))

                if (failureMessage != null) {
                    Text(
                        text = failureMessage,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (stackTrace != null) {
                    val context = LocalContext.current
                    TextButton(
                        onClick = {
                            context.copyToClipboard(
                                label = context.stringResource(MR.strings.ext_copy_stacktrace),
                                content = stackTrace,
                            )
                        },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(text = stringResource(MR.strings.ext_copy_stacktrace))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClickConfirm) {
                Text(text = stringResource(confirmLabel))
            }
        },
        dismissButton = {
            TextButton(onClick = onClickUninstall) {
                Text(text = stringResource(MR.strings.ext_uninstall))
            }
        },
        onDismissRequest = onDismissRequest,
    )
}
// RK <--

// RK: public so the Reikai unified Browse view can host the same prompt.
@Composable
fun ExtensionTrustDialog(
    onClickConfirm: () -> Unit,
    onClickDismiss: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        title = {
            Text(text = stringResource(MR.strings.untrusted_extension))
        },
        text = {
            Text(text = stringResource(MR.strings.untrusted_extension_message))
        },
        confirmButton = {
            TextButton(onClick = onClickConfirm) {
                Text(text = stringResource(MR.strings.ext_trust))
            }
        },
        dismissButton = {
            TextButton(onClick = onClickDismiss) {
                Text(text = stringResource(MR.strings.ext_uninstall))
            }
        },
        onDismissRequest = onDismissRequest,
    )
}

// RK: moved here from the deleted ExtensionsTab.kt, beside the other extension dialog, so both stay
//     upstream-tracked in one place. See the off-path manifest.
@Composable
fun ExtensionUninstallConfirmation(
    extensionName: String,
    onClickConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        title = {
            Text(text = stringResource(MR.strings.ext_confirm_remove))
        },
        text = {
            Text(text = stringResource(MR.strings.remove_private_extension_message, extensionName))
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onClickConfirm()
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.ext_remove))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        onDismissRequest = onDismissRequest,
    )
}
