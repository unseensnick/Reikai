package eu.kanade.presentation.more.settings.screen

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import exh.source.ExhPreferences
import exh.ui.login.EhLoginActivity
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Focused E-Hentai settings screen (Phase B2). Covers the website account / display options and the
 * server profile upload (uconfig). The favorites-sync and gallery-update-checker groups depend on
 * later EXH phases and are intentionally omitted for now.
 */
object SettingsEhScreen : SearchableSettings {
    private fun readResolve(): Any = SettingsEhScreen

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_eh

    /**
     * Re-uploads the server profile whenever a setting that feeds it changes.
     */
    @Composable
    private fun Reconfigure(
        exhPreferences: ExhPreferences,
        openWarnConfigureDialogController: () -> Unit,
    ) {
        var initialLoadGuard by remember { mutableStateOf(false) }
        val useHentaiAtHome by exhPreferences.useHentaiAtHome().collectAsState()
        val useJapaneseTitle by exhPreferences.useJapaneseTitle().collectAsState()
        val useOriginalImages by exhPreferences.exhUseOriginalImages().collectAsState()
        val ehTagFilterValue by exhPreferences.ehTagFilterValue().collectAsState()
        val ehTagWatchingValue by exhPreferences.ehTagWatchingValue().collectAsState()
        val imageQuality by exhPreferences.imageQuality().collectAsState()
        DisposableEffect(
            useHentaiAtHome,
            useJapaneseTitle,
            useOriginalImages,
            ehTagFilterValue,
            ehTagWatchingValue,
            imageQuality,
        ) {
            if (initialLoadGuard) {
                openWarnConfigureDialogController()
            }
            initialLoadGuard = true
            onDispose {}
        }
    }

    @Composable
    override fun getPreferences(): List<Preference> {
        val exhPreferences: ExhPreferences = remember { Injekt.get() }
        val exhentaiEnabled by exhPreferences.enableExhentai().collectAsState()
        var runConfigureDialog by remember { mutableStateOf(false) }
        val openWarnConfigureDialogController = { runConfigureDialog = true }

        Reconfigure(exhPreferences, openWarnConfigureDialogController)

        ConfigureExhDialog(run = runConfigureDialog, onRunning = { runConfigureDialog = false })

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.ehentai_prefs_account_settings),
                preferenceItems = listOf(
                    getLoginPreference(exhPreferences, openWarnConfigureDialogController),
                    useHentaiAtHome(exhentaiEnabled, exhPreferences),
                    useJapaneseTitle(exhentaiEnabled, exhPreferences),
                    useOriginalImages(exhentaiEnabled, exhPreferences),
                    watchedTags(exhentaiEnabled),
                    tagFilterThreshold(exhentaiEnabled, exhPreferences),
                    tagWatchingThreshold(exhentaiEnabled, exhPreferences),
                    watchedListDefaultState(exhentaiEnabled, exhPreferences),
                    imageQuality(exhentaiEnabled, exhPreferences),
                    enhancedEhentaiView(exhPreferences),
                ),
            ),
        )
    }

    @Composable
    private fun getLoginPreference(
        exhPreferences: ExhPreferences,
        openWarnConfigureDialogController: () -> Unit,
    ): Preference.PreferenceItem.SwitchPreference {
        val activityResultContract =
            rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                if (it.resultCode == Activity.RESULT_OK) {
                    // Upload settings
                    openWarnConfigureDialogController()
                }
            }
        val context = LocalContext.current
        val value by exhPreferences.enableExhentai().collectAsState()
        return Preference.PreferenceItem.SwitchPreference(
            preference = exhPreferences.enableExhentai(),
            title = stringResource(MR.strings.enable_exhentai),
            subtitle = if (!value) {
                stringResource(MR.strings.requires_login)
            } else {
                null
            },
            onValueChanged = { newVal ->
                if (!newVal) {
                    exhPreferences.enableExhentai().set(false)
                    true
                } else {
                    activityResultContract.launch(EhLoginActivity.newIntent(context))
                    false
                }
            },
        )
    }

    @Composable
    private fun useHentaiAtHome(
        exhentaiEnabled: Boolean,
        exhPreferences: ExhPreferences,
    ): Preference.PreferenceItem.ListPreference<Int> {
        return Preference.PreferenceItem.ListPreference(
            preference = exhPreferences.useHentaiAtHome(),
            entries = mapOf(
                0 to stringResource(MR.strings.use_hentai_at_home_option_1),
                1 to stringResource(MR.strings.use_hentai_at_home_option_2),
            ),
            title = stringResource(MR.strings.use_hentai_at_home),
            subtitle = stringResource(MR.strings.use_hentai_at_home_summary),
            enabled = exhentaiEnabled,
        )
    }

    @Composable
    private fun useJapaneseTitle(
        exhentaiEnabled: Boolean,
        exhPreferences: ExhPreferences,
    ): Preference.PreferenceItem.SwitchPreference {
        val value by exhPreferences.useJapaneseTitle().collectAsState()
        return Preference.PreferenceItem.SwitchPreference(
            preference = exhPreferences.useJapaneseTitle(),
            title = stringResource(MR.strings.show_japanese_titles),
            subtitle = if (value) {
                stringResource(MR.strings.show_japanese_titles_option_1)
            } else {
                stringResource(MR.strings.show_japanese_titles_option_2)
            },
            enabled = exhentaiEnabled,
        )
    }

    @Composable
    private fun useOriginalImages(
        exhentaiEnabled: Boolean,
        exhPreferences: ExhPreferences,
    ): Preference.PreferenceItem.SwitchPreference {
        val value by exhPreferences.exhUseOriginalImages().collectAsState()
        return Preference.PreferenceItem.SwitchPreference(
            preference = exhPreferences.exhUseOriginalImages(),
            title = stringResource(MR.strings.use_original_images),
            subtitle = if (value) {
                stringResource(MR.strings.use_original_images_on)
            } else {
                stringResource(MR.strings.use_original_images_off)
            },
            enabled = exhentaiEnabled,
        )
    }

    @Composable
    private fun watchedTags(exhentaiEnabled: Boolean): Preference.PreferenceItem.TextPreference {
        val context = LocalContext.current
        return Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.watched_tags),
            subtitle = stringResource(MR.strings.watched_tags_summary),
            enabled = exhentaiEnabled,
            onClick = {
                context.startActivity(
                    WebViewActivity.newIntent(
                        context,
                        url = "https://exhentai.org/mytags",
                        title = context.stringResource(MR.strings.watched_tags_exh),
                    ),
                )
            },
        )
    }

    @Composable
    private fun TagThresholdDialog(
        onDismissRequest: () -> Unit,
        title: String,
        initialValue: Int,
        valueRange: IntRange,
        outsideRangeError: String,
        onValueChange: (Int) -> Unit,
    ) {
        var value by remember(initialValue) {
            mutableStateOf(initialValue.toString())
        }
        val isValid = remember(value) { value.toIntOrNull().let { it != null && it in valueRange } }
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = {
                TextButton(
                    onClick = { onValueChange(value.toIntOrNull() ?: return@TextButton) },
                    enabled = isValid,
                ) {
                    Text(text = stringResource(MR.strings.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
            title = {
                Text(text = title)
            },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                ) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        maxLines = 1,
                        singleLine = true,
                        isError = !isValid,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = if (!isValid) {
                            { Icon(Icons.Outlined.Error, outsideRangeError) }
                        } else {
                            null
                        },
                    )
                    if (!isValid) {
                        Text(
                            text = outsideRangeError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            },
        )
    }

    @Composable
    private fun tagFilterThreshold(
        exhentaiEnabled: Boolean,
        exhPreferences: ExhPreferences,
    ): Preference.PreferenceItem.TextPreference {
        val value by exhPreferences.ehTagFilterValue().collectAsState()
        var dialogOpen by remember { mutableStateOf(false) }
        if (dialogOpen) {
            TagThresholdDialog(
                onDismissRequest = { dialogOpen = false },
                title = stringResource(MR.strings.tag_filtering_threshold),
                initialValue = value,
                valueRange = -9999..0,
                outsideRangeError = stringResource(MR.strings.tag_filtering_threshhold_error),
                onValueChange = {
                    dialogOpen = false
                    exhPreferences.ehTagFilterValue().set(it)
                },
            )
        }
        return Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.tag_filtering_threshold),
            subtitle = stringResource(MR.strings.tag_filtering_threshhold_summary, value),
            enabled = exhentaiEnabled,
            onClick = {
                dialogOpen = true
            },
        )
    }

    @Composable
    private fun tagWatchingThreshold(
        exhentaiEnabled: Boolean,
        exhPreferences: ExhPreferences,
    ): Preference.PreferenceItem.TextPreference {
        val value by exhPreferences.ehTagWatchingValue().collectAsState()
        var dialogOpen by remember { mutableStateOf(false) }
        if (dialogOpen) {
            TagThresholdDialog(
                onDismissRequest = { dialogOpen = false },
                title = stringResource(MR.strings.tag_watching_threshhold),
                initialValue = value,
                valueRange = 0..9999,
                outsideRangeError = stringResource(MR.strings.tag_watching_threshhold_error),
                onValueChange = {
                    dialogOpen = false
                    exhPreferences.ehTagWatchingValue().set(it)
                },
            )
        }
        return Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.tag_watching_threshhold),
            subtitle = stringResource(MR.strings.tag_watching_threshhold_summary, value),
            enabled = exhentaiEnabled,
            onClick = {
                dialogOpen = true
            },
        )
    }

    @Composable
    private fun watchedListDefaultState(
        exhentaiEnabled: Boolean,
        exhPreferences: ExhPreferences,
    ): Preference.PreferenceItem.SwitchPreference {
        return Preference.PreferenceItem.SwitchPreference(
            preference = exhPreferences.exhWatchedListDefaultState(),
            title = stringResource(MR.strings.watched_list_default),
            subtitle = stringResource(MR.strings.watched_list_state_summary),
            enabled = exhentaiEnabled,
        )
    }

    @Composable
    private fun imageQuality(
        exhentaiEnabled: Boolean,
        exhPreferences: ExhPreferences,
    ): Preference.PreferenceItem.ListPreference<String> {
        return Preference.PreferenceItem.ListPreference(
            preference = exhPreferences.imageQuality(),
            entries = mapOf(
                "auto" to stringResource(MR.strings.eh_image_quality_auto),
                "ovrs_2400" to stringResource(MR.strings.eh_image_quality_2400),
                "ovrs_1600" to stringResource(MR.strings.eh_image_quality_1600),
                "high" to stringResource(MR.strings.eh_image_quality_1280),
                "med" to stringResource(MR.strings.eh_image_quality_980),
                "low" to stringResource(MR.strings.eh_image_quality_780),
            ),
            title = stringResource(MR.strings.eh_image_quality),
            subtitle = stringResource(MR.strings.eh_image_quality_summary),
            enabled = exhentaiEnabled,
        )
    }

    @Composable
    private fun enhancedEhentaiView(exhPreferences: ExhPreferences): Preference.PreferenceItem.SwitchPreference {
        return Preference.PreferenceItem.SwitchPreference(
            preference = exhPreferences.enhancedEHentaiView(),
            title = stringResource(MR.strings.pref_enhanced_e_hentai_view),
            subtitle = stringResource(MR.strings.pref_enhanced_e_hentai_view_summary),
        )
    }
}
