package reikai.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.manga.components.MarkdownRender
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import org.intellij.markdown.MarkdownTokenTypes.Companion.EOL
import org.intellij.markdown.MarkdownTokenTypes.Companion.WHITE_SPACE
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * GitHub's `> [!WARNING]` callouts, drawn for the release notes on the What's new screen.
 *
 * The GFM parser emits these as their own node type, which the markdown renderer has no component
 * for, so they reach [eu.kanade.presentation.manga.components.MarkdownRender]'s custom slot. The
 * accent colours are fixed rather than taken from the theme: five types have to stay apart from one
 * another, and no Material role set carries five distinguishable ones.
 */
private val ALERT_BAR_WIDTH = 3.dp
private val ALERT_CORNER = 8.dp
private const val ALERT_TINT_ALPHA = 0.10f
private val ALERT_BLOCK_GAP = 8.dp

enum class GitHubAlertType(
    val titleRes: StringResource,
    private val light: Color,
    private val dark: Color,
) {
    NOTE(MR.strings.markdown_alert_note, Color(0xFF0969DA), Color(0xFF4493F8)),
    TIP(MR.strings.markdown_alert_tip, Color(0xFF1A7F37), Color(0xFF3FB950)),
    IMPORTANT(MR.strings.markdown_alert_important, Color(0xFF8250DF), Color(0xFFAB7DF8)),
    WARNING(MR.strings.markdown_alert_warning, Color(0xFF9A6700), Color(0xFFD29922)),
    CAUTION(MR.strings.markdown_alert_caution, Color(0xFFCF222E), Color(0xFFF85149)),
    ;

    fun accent(onDarkSurface: Boolean): Color = if (onDarkSurface) dark else light
}

/**
 * The type named by the alert's title token, or null when it names none. The parser only opens an
 * alert on one of the five markers, so null means this enum and the parser have drifted apart.
 */
internal fun alertTypeOf(alert: ASTNode, content: String): GitHubAlertType? {
    val title = alert.children.firstOrNull { it.type == GFMTokenTypes.ALERT_TITLE }
        ?.getTextInNode(content)
        ?.toString()
        ?: return null
    val name = title.removeSurrounding("[!", "]")
    return GitHubAlertType.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
}

/**
 * The blocks to draw inside the callout. An alert's first two children are the bare `>` marker and
 * the title token, and its blocks are separated by end-of-line and whitespace tokens that carry the
 * `>` of each continued line; all of those would draw as stray quote bars and gaps.
 */
internal fun alertBodyNodes(alert: ASTNode): List<ASTNode> = alert.children
    .dropWhile { it.type != GFMTokenTypes.ALERT_TITLE }
    .drop(1)
    .filterNot { it.type == EOL || it.type == WHITE_SPACE }

@Composable
fun MarkdownAlert(model: MarkdownComponentModel) {
    val type = alertTypeOf(model.node, model.content)
    val accent = type?.accent(onDarkSurface = MaterialTheme.colorScheme.surface.luminance() < 0.5f)
        ?: MaterialTheme.colorScheme.onSurfaceVariant
    val components = LocalMarkdownComponents.current
    val barWidth = with(LocalDensity.current) { ALERT_BAR_WIDTH.toPx() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ALERT_CORNER))
            .background(accent.copy(alpha = ALERT_TINT_ALPHA))
            .drawBehind { drawRect(color = accent, size = Size(barWidth, size.height)) }
            .padding(start = 12.dp + ALERT_BAR_WIDTH, top = 10.dp, end = 12.dp, bottom = 10.dp),
    ) {
        if (type != null) {
            Text(
                text = stringResource(type.titleRes),
                style = MaterialTheme.typography.titleSmall,
                color = accent,
            )
            Spacer(Modifier.height(4.dp))
        }
        alertBodyNodes(model.node).forEachIndexed { index, child ->
            key(child.startOffset) {
                // Spaced here rather than through the renderer's own block padding, which is 2dp
                // and leaves two blocks reading as one inside a tinted box.
                if (index > 0) Spacer(Modifier.height(ALERT_BLOCK_GAP))
                MarkdownElement(
                    node = child,
                    components = components,
                    content = model.content,
                    includeSpacer = false,
                )
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun MarkdownAlertPreview() {
    TachiyomiPreviewTheme {
        Surface {
            MarkdownRender(
                content = """
                    > [!NOTE]
                    > Something worth knowing, with **bold** and a [link](https://reikai.app).

                    > [!TIP]
                    > A shortcut that makes something easier.

                    > [!IMPORTANT]
                    > - it survives a list
                    > - and a second item

                    > [!WARNING]
                    > First paragraph of a warning.
                    >
                    > Second paragraph of the same warning.

                    > [!CAUTION]
                    > Something that loses data.

                    > A plain quote, for comparison.
                """.trimIndent(),
                flavour = remember { GFMFlavourDescriptor() },
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
