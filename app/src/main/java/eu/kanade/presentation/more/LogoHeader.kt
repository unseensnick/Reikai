package eu.kanade.presentation.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import mihon.app.di.appGraph

@Composable
fun LogoHeader(
    iconPadding: PaddingValues = PaddingValues(),
) {
    // RK --> the logo of the design the launcher shows, so the picked app icon carries into the app
    val context = LocalContext.current
    val shown by remember { context.appGraph.launcherIconState }.shown.collectAsState()
    // RK <--
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // RK --> nothing drawn until the launcher's icon is read, rather than a logo that then changes
        val logo = shown?.design?.logo
        if (logo == null) {
            Spacer(Modifier.padding(iconPadding).size(72.dp))
        } else {
            Icon(
                painter = painterResource(logo),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(iconPadding)
                    // RK: 72 rather than 64, because the glyph sits inside a line-art flame. This draws
                    //     it as tall as Mihon's glyph.
                    .size(72.dp),
            )
        }
        // RK <--

        HorizontalDivider()
    }
}
