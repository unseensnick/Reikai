package reikai.presentation.browse.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import reikai.domain.library.ContentType
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Says which content type a Browse row belongs to, beside its name. Drawn only where the list holds
 * both, since the rows are interleaved by language there and nothing else on the row can say.
 */
@Composable
fun ContentTypeBadge(
    contentType: ContentType,
    modifier: Modifier = Modifier,
) {
    val label = when (contentType) {
        ContentType.MANGA -> MR.strings.content_type_manga
        ContentType.NOVELS -> MR.strings.content_type_novels
        ContentType.ALL -> return
    }
    Text(
        text = stringResource(label),
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 4.dp, vertical = 1.dp),
        // Sits inline with the name, so it stays a shade smaller than the name's own style.
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
