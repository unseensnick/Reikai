package eu.kanade.presentation.browse

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.browse.components.BaseBrowseItem
import eu.kanade.presentation.browse.components.ExtensionIcon
import eu.kanade.presentation.browse.components.label
import eu.kanade.presentation.manga.components.DotSeparatorNoSpaceText
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionUiModel
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.copyToClipboard
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.Info
import mihon.icons.materialsymbols.rounded.Public
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Settings
import mihon.icons.materialsymbols.rounded.VerifiedUser
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha

// RK: partially collapsed. The screen, its sectioned list, pull-to-refresh, the install-permission
//     banner and the loading and empty states moved to the shared Extensions engine and tab
//     (reikai/presentation/browse/extension/). What is left is the manga extension row and the
//     trust, not-loaded and uninstall dialogs it raises, which the shared list draws for its manga
//     half, plus the NotLoadedDialog body that the novel plugin rows share.

// RK: public so the Reikai unified Browse view can reuse the manga extension row verbatim.
@Composable
fun ExtensionItem(
    item: ExtensionUiModel.Item,
    onClickItem: (Extension) -> Unit,
    onLongClickItem: (Extension) -> Unit,
    onClickItemCancel: (Extension) -> Unit,
    onClickItemAction: (Extension) -> Unit,
    onClickItemSecondaryAction: (Extension) -> Unit,
    modifier: Modifier = Modifier,
    // RK: content-type badge, beside the name, drawn by the shared list when it holds both types.
    badge: @Composable () -> Unit = {},
) {
    val (extension, installStep) = item
    BaseBrowseItem(
        modifier = modifier
            .combinedClickable(
                onClick = { onClickItem(extension) },
                onLongClick = { onLongClickItem(extension) },
            ),
        onClickItem = { onClickItem(extension) },
        onLongClickItem = { onLongClickItem(extension) },
        icon = {
            Box(
                modifier = Modifier
                    .size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                val idle = installStep.isCompleted()
                if (!idle) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
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
                        .padding(padding),
                )
            }
        },
        action = {
            ExtensionItemActions(
                extension = extension,
                installStep = installStep,
                onClickItemCancel = onClickItemCancel,
                onClickItemAction = onClickItemAction,
                onClickItemSecondaryAction = onClickItemSecondaryAction,
            )
        },
    ) {
        ExtensionItemContent(
            extension = extension,
            installStep = installStep,
            badge = badge,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ExtensionItemContent(
    extension: Extension,
    installStep: InstallStep,
    modifier: Modifier = Modifier,
    // RK: content-type badge, beside the name.
    badge: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier.padding(start = MaterialTheme.padding.medium),
    ) {
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
        // Won't look good but it's not like we can ellipsize overflowing content
        FlowRow(
            modifier = Modifier.secondaryItemAlpha(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
        ) {
            ProvideTextStyle(value = MaterialTheme.typography.bodySmall) {
                var hasAlreadyShownAnElement by remember { mutableStateOf(false) }
                if (extension is Extension.Loaded && extension.lang.isNotEmpty()) {
                    hasAlreadyShownAnElement = true
                    Text(
                        text = LocaleHelper.getSourceDisplayName(extension.lang, LocalContext.current),
                    )
                }

                if (extension.versionName.isNotEmpty()) {
                    if (hasAlreadyShownAnElement) DotSeparatorNoSpaceText()
                    hasAlreadyShownAnElement = true
                    Text(
                        text = extension.versionName,
                    )
                }

                val warnings = listOfNotNull(
                    when {
                        extension is Extension.NotLoaded ->
                            extension.reason.labelRes?.let { it to MaterialTheme.colorScheme.error }
                        extension is Extension.Loaded && extension.isObsolete ->
                            MR.strings.ext_obsolete to MaterialTheme.colorScheme.error
                        else -> null
                    },
                    extension.contentWarning.label?.let { it.title to it.color },
                )
                warnings.forEach { (label, color) ->
                    if (hasAlreadyShownAnElement) DotSeparatorNoSpaceText()
                    hasAlreadyShownAnElement = true
                    Text(
                        text = stringResource(label).uppercase(),
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (extension is Extension.Loaded && !extension.isShared) {
                    if (hasAlreadyShownAnElement) DotSeparatorNoSpaceText()
                    Text(
                        text = stringResource(MR.strings.ext_installer_private),
                    )
                }

                if (!installStep.isCompleted()) {
                    DotSeparatorNoSpaceText()
                    Text(
                        text = when (installStep) {
                            InstallStep.Pending -> stringResource(MR.strings.ext_pending)
                            InstallStep.Downloading -> stringResource(MR.strings.ext_downloading)
                            InstallStep.Installing -> stringResource(MR.strings.ext_installing)
                            else -> error("Must not show non-install process text")
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtensionItemActions(
    extension: Extension,
    installStep: InstallStep,
    modifier: Modifier = Modifier,
    onClickItemCancel: (Extension) -> Unit = {},
    onClickItemAction: (Extension) -> Unit = {},
    onClickItemSecondaryAction: (Extension) -> Unit = {},
) {
    val isIdle = installStep.isCompleted()

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        when {
            !isIdle -> {
                IconButton(onClick = { onClickItemCancel(extension) }) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Close,
                        contentDescription = stringResource(MR.strings.action_cancel),
                    )
                }
            }
            installStep == InstallStep.Error -> {
                IconButton(onClick = { onClickItemAction(extension) }) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Refresh,
                        contentDescription = stringResource(MR.strings.action_retry),
                    )
                }
            }
            installStep == InstallStep.Idle -> {
                when (extension) {
                    is Extension.Loaded -> {
                        IconButton(onClick = { onClickItemSecondaryAction(extension) }) {
                            Icon(
                                imageVector = MaterialSymbols.Rounded.Settings,
                                contentDescription = stringResource(MR.strings.action_settings),
                            )
                        }

                        if (extension.hasUpdate) {
                            IconButton(onClick = { onClickItemAction(extension) }) {
                                Icon(
                                    imageVector = MaterialSymbols.Rounded.Download,
                                    contentDescription = stringResource(MR.strings.ext_update),
                                )
                            }
                        }
                    }
                    is Extension.NotLoaded -> {
                        val isUntrusted = extension.reason is Extension.NotLoaded.Reason.Untrusted
                        IconButton(onClick = { onClickItemAction(extension) }) {
                            Icon(
                                imageVector = if (isUntrusted) {
                                    MaterialSymbols.Rounded.VerifiedUser
                                } else {
                                    MaterialSymbols.Rounded.Info
                                },
                                contentDescription = if (isUntrusted) {
                                    stringResource(MR.strings.ext_trust)
                                } else {
                                    stringResource(MR.strings.ext_not_loaded)
                                },
                            )
                        }
                    }
                    is Extension.Available -> {
                        if (extension.sources.isNotEmpty()) {
                            IconButton(
                                onClick = { onClickItemSecondaryAction(extension) },
                            ) {
                                Icon(
                                    imageVector = MaterialSymbols.Rounded.Public,
                                    contentDescription = stringResource(MR.strings.action_open_in_web_view),
                                )
                            }
                        }

                        IconButton(onClick = { onClickItemAction(extension) }) {
                            Icon(
                                imageVector = MaterialSymbols.Rounded.Download,
                                contentDescription = stringResource(MR.strings.ext_install),
                            )
                        }
                    }
                }
            }
        }
    }
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
) {
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
