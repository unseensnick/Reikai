package exh.assets.ehassets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import exh.assets.EhAssets

/**
 * The blocky "EH" E-Hentai mark, traced from the site favicon. Rendered as a tinted [ImageVector] in
 * the settings list, so only the silhouette matters (the brand red is replaced by the row tint).
 */
val EhAssets.EhLogo: ImageVector
    get() {
        _ehLogo?.let { return it }
        _ehLogo = ImageVector.Builder(
            name = "EhLogo",
            defaultWidth = 24.0.dp,
            defaultHeight = 24.0.dp,
            viewportWidth = 140f,
            viewportHeight = 120f,
        ).apply {
            path(fill = SolidColor(Color(0xFF660611))) {
                // E
                moveTo(0f, 0f); horizontalLineToRelative(20f); verticalLineToRelative(120f); horizontalLineToRelative(-20f); close()
                moveTo(20f, 0f); horizontalLineToRelative(30f); verticalLineToRelative(20f); horizontalLineToRelative(-30f); close()
                moveTo(20f, 50f); horizontalLineToRelative(20f); verticalLineToRelative(20f); horizontalLineToRelative(-20f); close()
                moveTo(50f, 50f); horizontalLineToRelative(20f); verticalLineToRelative(20f); horizontalLineToRelative(-20f); close()
                moveTo(20f, 100f); horizontalLineToRelative(30f); verticalLineToRelative(20f); horizontalLineToRelative(-30f); close()
                // H
                moveTo(80f, 0f); horizontalLineToRelative(20f); verticalLineToRelative(120f); horizontalLineToRelative(-20f); close()
                moveTo(100f, 50f); horizontalLineToRelative(20f); verticalLineToRelative(20f); horizontalLineToRelative(-20f); close()
                moveTo(120f, 0f); horizontalLineToRelative(20f); verticalLineToRelative(120f); horizontalLineToRelative(-20f); close()
            }
        }.build()
        return _ehLogo!!
    }

@Suppress("ObjectPropertyName")
private var _ehLogo: ImageVector? = null
