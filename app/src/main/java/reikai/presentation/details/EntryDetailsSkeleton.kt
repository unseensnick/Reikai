package reikai.presentation.details

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The details page's shape while it loads, for both content types: cover, titles, the action row, a
 * description and chapter rows, in place of a spinner. A novel opened from Browse stays here for its
 * whole first fetch, so the page takes the shape it is about to have rather than jumping into it.
 */
@Composable
fun EntryDetailsSkeleton(modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900), RepeatMode.Reverse),
        label = "skeleton-alpha",
    )
    val loading = stringResource(MR.strings.loading)
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(start = 16.dp, top = APP_BAR_HEIGHT + 16.dp, end = 16.dp)
            .alpha(pulse)
            .semantics { contentDescription = loading },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Bone(Modifier.width(100.dp).aspectRatio(2f / 3f))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Bone(Modifier.fillMaxWidth(0.8f).height(22.dp))
                Bone(Modifier.fillMaxWidth(0.5f).height(14.dp))
                Bone(Modifier.fillMaxWidth(0.4f).height(14.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            repeat(ACTIONS) { Bone(Modifier.width(56.dp).height(44.dp)) }
        }
        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Bone(Modifier.fillMaxWidth().height(12.dp))
            Bone(Modifier.fillMaxWidth().height(12.dp))
            Bone(Modifier.fillMaxWidth(0.6f).height(12.dp))
        }
        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            repeat(CHAPTERS) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Bone(Modifier.fillMaxWidth(0.7f).height(14.dp))
                    Bone(Modifier.fillMaxWidth(0.35f).height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun Bone(modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

private val APP_BAR_HEIGHT = 56.dp
private const val ACTIONS = 4
private const val CHAPTERS = 8
