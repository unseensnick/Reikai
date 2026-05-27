package yokai.presentation.settings.screen.advanced

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import dev.icerock.moko.resources.compose.stringResource
import yokai.i18n.MR
import yokai.presentation.AppBarType
import yokai.presentation.component.preference.widget.TextPreferenceWidget
import yokai.presentation.settings.SettingsScaffold
import yokai.util.Screen

class StoryBookScreen : Screen() {

    @Composable
    override fun Content() {
        val textFieldState = rememberTextFieldState()
        val listState = rememberLazyListState()

        SettingsScaffold(
            title = stringResource(MR.strings.about),
            appBarType = AppBarType.SMALL,
            appBarScrollBehavior = null,
            textFieldState = textFieldState,
            content = { contentPadding ->
                LazyColumn(
                    contentPadding = contentPadding,
                    state = listState,
                ) {
                    items(100) {
                        TextPreferenceWidget(
                            title = "Item #${it + 1}",
                            onPreferenceClick = {},
                        )
                    }
                }
            },
        )
    }
}
