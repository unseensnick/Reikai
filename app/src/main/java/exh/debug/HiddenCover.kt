package exh.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import eu.kanade.tachiyomi.R
import mihon.app.di.appGraph
import tachiyomi.presentation.core.util.collectAsState

/** [DebugToggles.HIDE_COVER_IMAGE_ONLY_SHOW_COLOR], provided once per window so every cover reads one value. */
val LocalCoverImagesHidden = staticCompositionLocalOf { false }

@Composable
fun rememberCoverImagesHidden(): Boolean {
    val context = LocalContext.current
    val preference = remember {
        DebugToggles.HIDE_COVER_IMAGE_ONLY_SHOW_COLOR.preference(context.appGraph.preferenceStore)
    }
    val hidden by preference.collectAsState()
    return hidden
}

/** A cover's place drawn as Komikku draws a hidden one: its placeholder colour and a book mark. */
@Composable
fun HiddenCover(background: Color, modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(background)) {
        Icon(
            painter = painterResource(R.drawable.ic_book_24dp),
            contentDescription = null,
            tint = HiddenCoverMarkColor,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize(0.4f),
        )
    }
}

private val HiddenCoverMarkColor = Color(0x8F888888)
