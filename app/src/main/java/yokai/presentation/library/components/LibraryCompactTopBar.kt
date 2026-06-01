package yokai.presentation.library.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.getResourceColor

/**
 * Compact, pinned library top bar. Replaces the collapsing [yokai.presentation.component.ReikaiLargeTopBar]
 * on the Compose library surface: a single app-bar row hosting the Manga / Light novels content
 * toggle on the left, then a flexible spacer, then search / filter / overflow on the right. The
 * search icon swaps the row in place into a back-arrow + text field + clear form (mirrors the
 * legacy `MiniSearchView.onActionViewExpanded`).
 *
 * The bar never collapses on scroll. The scrollable category tab row (single-category mode) is
 * rendered by [below], flush under the app-bar row so it reads as part of the bar.
 *
 * Colors read straight off the legacy theme attrs (`?attr/background` for the surface,
 * `?attr/actionBarTintColor` for content), the same path the legacy library uses, because
 * `createMdc3Theme` does not surface those custom Reikai attrs as M3 ColorScheme tokens.
 */
@Composable
fun LibraryCompactTopBar(
    contentToggle: @Composable () -> Unit,
    onSearchClick: () -> Unit,
    searchActive: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearchClose: () -> Unit,
    searchHint: String,
    actions: @Composable RowScope.() -> Unit,
    below: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val containerColor = remember(context) { Color(context.getResourceColor(R.attr.background)) }
    val contentColor = remember(context) { Color(context.getResourceColor(R.attr.actionBarTintColor)) }

    Surface(
        modifier = modifier,
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (searchActive) {
                    SearchRow(
                        query = searchQuery,
                        hint = searchHint,
                        onQueryChange = onSearchQueryChange,
                        onClose = onSearchClose,
                        contentColor = contentColor,
                    )
                } else {
                    contentToggle()
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = onSearchClick) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null,
                            tint = contentColor,
                        )
                    }
                    actions()
                }
            }
            below()
        }
    }
}

@Composable
private fun RowScope.SearchRow(
    query: String,
    hint: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    contentColor: Color,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    IconButton(onClick = onClose) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = null,
            tint = contentColor,
        )
    }
    // TextFieldValue overload (not the String one): the String overload collapses the
    // cursor/selection across recompositions on some IMEs, which reverses typed input
    // ("solo" -> "olos"). Owning the TextFieldValue here keeps the caret stable; we only
    // re-seed it when `query` changes externally (e.g. the clear button), never mid-typing.
    var fieldValue by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    if (fieldValue.text != query) {
        fieldValue = TextFieldValue(query, TextRange(query.length))
    }
    BasicTextField(
        value = fieldValue,
        onValueChange = {
            fieldValue = it
            if (it.text != query) onQueryChange(it.text)
        },
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 16.sp,
            color = contentColor,
        ),
        cursorBrush = SolidColor(contentColor),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier
            .weight(1f)
            .focusRequester(focusRequester),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = hint,
                        fontSize = 16.sp,
                        color = contentColor.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                innerTextField()
            }
        },
    )
    if (query.isNotEmpty()) {
        IconButton(onClick = { onQueryChange("") }) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = null,
                tint = contentColor,
            )
        }
    }
}
