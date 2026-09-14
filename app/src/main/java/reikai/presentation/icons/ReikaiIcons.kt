package reikai.presentation.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The glyphs Reikai draws that Mihon's Material Symbols set does not ship, because Mihon has none of
 * the surfaces using them (novel reader typography, the reader action row and settings sheet, read-aloud
 * controls, gallery ratings).
 *
 * Path data and both helpers are copied verbatim from androidx.compose.material material-icons
 * (Apache 2.0), Rounded variants. Never redraw one by hand; copy it. See upstream-sync.md.
 */
object ReikaiIcons

private const val ICON_DIMENSION = 24f

private inline fun materialIcon(
    name: String,
    autoMirror: Boolean = false,
    block: ImageVector.Builder.() -> ImageVector.Builder,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = ICON_DIMENSION.dp,
    defaultHeight = ICON_DIMENSION.dp,
    viewportWidth = ICON_DIMENSION,
    viewportHeight = ICON_DIMENSION,
    autoMirror = autoMirror,
).block().build()

private inline fun ImageVector.Builder.materialPath(
    fillAlpha: Float = 1f,
    strokeAlpha: Float = 1f,
    pathFillType: PathFillType = PathFillType.NonZero,
    pathBuilder: PathBuilder.() -> Unit,
) = path(
    fill = SolidColor(Color.Black),
    fillAlpha = fillAlpha,
    stroke = null,
    strokeAlpha = strokeAlpha,
    strokeLineWidth = 1f,
    strokeLineCap = StrokeCap.Butt,
    strokeLineJoin = StrokeJoin.Bevel,
    strokeLineMiter = 1f,
    pathFillType = pathFillType,
    pathBuilder = pathBuilder,
)

val ReikaiIcons.FormatAlignLeft: ImageVector
    get() {
        if (formatAlignLeftCache != null) {
            return formatAlignLeftCache!!
        }
        formatAlignLeftCache = materialIcon(name = "Reikai.FormatAlignLeft") {
            materialPath {
                moveTo(14.0f, 15.0f)
                lineTo(4.0f, 15.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveToRelative(0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(10.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                close()
                moveTo(14.0f, 7.0f)
                lineTo(4.0f, 7.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveToRelative(0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(10.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                close()
                moveTo(4.0f, 13.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                lineTo(4.0f, 11.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveToRelative(0.45f, 1.0f, 1.0f, 1.0f)
                close()
                moveTo(4.0f, 21.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                lineTo(4.0f, 19.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveToRelative(0.45f, 1.0f, 1.0f, 1.0f)
                close()
                moveTo(3.0f, 4.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                lineTo(4.0f, 3.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                close()
            }
        }
        return formatAlignLeftCache!!
    }

private var formatAlignLeftCache: ImageVector? = null

val ReikaiIcons.FormatAlignCenter: ImageVector
    get() {
        if (formatAlignCenterCache != null) {
            return formatAlignCenterCache!!
        }
        formatAlignCenterCache = materialIcon(name = "Reikai.FormatAlignCenter") {
            materialPath {
                moveTo(7.0f, 16.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(8.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                lineTo(8.0f, 15.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                close()
                moveTo(4.0f, 21.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                lineTo(4.0f, 19.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveToRelative(0.45f, 1.0f, 1.0f, 1.0f)
                close()
                moveTo(4.0f, 13.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                lineTo(4.0f, 11.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveToRelative(0.45f, 1.0f, 1.0f, 1.0f)
                close()
                moveTo(7.0f, 8.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(8.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                lineTo(8.0f, 7.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                close()
                moveTo(3.0f, 4.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                lineTo(4.0f, 3.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                close()
            }
        }
        return formatAlignCenterCache!!
    }

private var formatAlignCenterCache: ImageVector? = null

val ReikaiIcons.FormatAlignRight: ImageVector
    get() {
        if (formatAlignRightCache != null) {
            return formatAlignRightCache!!
        }
        formatAlignRightCache = materialIcon(name = "Reikai.FormatAlignRight") {
            materialPath {
                moveTo(4.0f, 21.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                lineTo(4.0f, 19.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveToRelative(0.45f, 1.0f, 1.0f, 1.0f)
                close()
                moveTo(10.0f, 17.0f)
                horizontalLineToRelative(10.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                lineTo(10.0f, 15.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveToRelative(0.45f, 1.0f, 1.0f, 1.0f)
                close()
                moveTo(4.0f, 13.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                lineTo(4.0f, 11.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveToRelative(0.45f, 1.0f, 1.0f, 1.0f)
                close()
                moveTo(10.0f, 9.0f)
                horizontalLineToRelative(10.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                lineTo(10.0f, 7.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveToRelative(0.45f, 1.0f, 1.0f, 1.0f)
                close()
                moveTo(3.0f, 4.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                lineTo(4.0f, 3.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                close()
            }
        }
        return formatAlignRightCache!!
    }

private var formatAlignRightCache: ImageVector? = null

val ReikaiIcons.FormatAlignJustify: ImageVector
    get() {
        if (formatAlignJustifyCache != null) {
            return formatAlignJustifyCache!!
        }
        formatAlignJustifyCache = materialIcon(name = "Reikai.FormatAlignJustify") {
            materialPath {
                moveTo(4.0f, 21.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                lineTo(4.0f, 19.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveToRelative(0.45f, 1.0f, 1.0f, 1.0f)
                close()
                moveTo(4.0f, 17.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                lineTo(4.0f, 15.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveToRelative(0.45f, 1.0f, 1.0f, 1.0f)
                close()
                moveTo(4.0f, 13.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                lineTo(4.0f, 11.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveToRelative(0.45f, 1.0f, 1.0f, 1.0f)
                close()
                moveTo(4.0f, 9.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                lineTo(4.0f, 7.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveToRelative(0.45f, 1.0f, 1.0f, 1.0f)
                close()
                moveTo(3.0f, 4.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                lineTo(4.0f, 3.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                close()
            }
        }
        return formatAlignJustifyCache!!
    }

private var formatAlignJustifyCache: ImageVector? = null

val ReikaiIcons.FormatSize: ImageVector
    get() {
        if (formatSizeCache != null) {
            return formatSizeCache!!
        }
        formatSizeCache = materialIcon(name = "Reikai.FormatSize") {
            materialPath {
                moveTo(9.0f, 5.5f)
                curveToRelative(0.0f, 0.83f, 0.67f, 1.5f, 1.5f, 1.5f)
                horizontalLineTo(14.0f)
                verticalLineToRelative(10.5f)
                curveToRelative(0.0f, 0.83f, 0.67f, 1.5f, 1.5f, 1.5f)
                reflectiveCurveToRelative(1.5f, -0.67f, 1.5f, -1.5f)
                verticalLineTo(7.0f)
                horizontalLineToRelative(3.5f)
                curveToRelative(0.83f, 0.0f, 1.5f, -0.67f, 1.5f, -1.5f)
                reflectiveCurveTo(21.33f, 4.0f, 20.5f, 4.0f)
                horizontalLineToRelative(-10.0f)
                curveTo(9.67f, 4.0f, 9.0f, 4.67f, 9.0f, 5.5f)
                close()
                moveTo(4.5f, 12.0f)
                horizontalLineTo(6.0f)
                verticalLineToRelative(5.5f)
                curveToRelative(0.0f, 0.83f, 0.67f, 1.5f, 1.5f, 1.5f)
                reflectiveCurveTo(9.0f, 18.33f, 9.0f, 17.5f)
                verticalLineTo(12.0f)
                horizontalLineToRelative(1.5f)
                curveToRelative(0.83f, 0.0f, 1.5f, -0.67f, 1.5f, -1.5f)
                reflectiveCurveTo(11.33f, 9.0f, 10.5f, 9.0f)
                horizontalLineToRelative(-6.0f)
                curveTo(3.67f, 9.0f, 3.0f, 9.67f, 3.0f, 10.5f)
                reflectiveCurveTo(3.67f, 12.0f, 4.5f, 12.0f)
                close()
            }
        }
        return formatSizeCache!!
    }

private var formatSizeCache: ImageVector? = null

val ReikaiIcons.Lightbulb: ImageVector
    get() {
        if (lightbulbCache != null) {
            return lightbulbCache!!
        }
        lightbulbCache = materialIcon(name = "Reikai.Lightbulb") {
            materialPath {
                moveTo(12.0f, 22.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                horizontalLineToRelative(-4.0f)
                curveTo(10.0f, 21.1f, 10.9f, 22.0f, 12.0f, 22.0f)
                close()
            }
            materialPath {
                moveTo(9.0f, 19.0f)
                horizontalLineToRelative(6.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(0.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineTo(9.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(0.0f)
                curveTo(8.0f, 18.55f, 8.45f, 19.0f, 9.0f, 19.0f)
                close()
            }
            materialPath {
                moveTo(12.0f, 2.0f)
                curveTo(7.86f, 2.0f, 4.5f, 5.36f, 4.5f, 9.5f)
                curveToRelative(0.0f, 3.82f, 2.66f, 5.86f, 3.77f, 6.5f)
                horizontalLineToRelative(7.46f)
                curveToRelative(1.11f, -0.64f, 3.77f, -2.68f, 3.77f, -6.5f)
                curveTo(19.5f, 5.36f, 16.14f, 2.0f, 12.0f, 2.0f)
                close()
            }
        }
        return lightbulbCache!!
    }

private var lightbulbCache: ImageVector? = null

val ReikaiIcons.SwipeVertical: ImageVector
    get() {
        if (swipeVerticalCache != null) {
            return swipeVerticalCache!!
        }
        swipeVerticalCache = materialIcon(name = "Reikai.SwipeVertical") {
            materialPath {
                moveTo(0.0f, 12.0f)
                curveToRelative(0.0f, 3.22f, 1.13f, 6.18f, 3.02f, 8.5f)
                horizontalLineTo(1.75f)
                curveTo(1.34f, 20.5f, 1.0f, 20.84f, 1.0f, 21.25f)
                reflectiveCurveTo(1.34f, 22.0f, 1.75f, 22.0f)
                horizontalLineTo(5.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(-3.25f)
                curveTo(6.0f, 17.34f, 5.66f, 17.0f, 5.25f, 17.0f)
                curveToRelative(-0.41f, 0.0f, -0.75f, 0.34f, -0.75f, 0.75f)
                verticalLineToRelative(2.16f)
                curveToRelative(-1.86f, -2.11f, -3.0f, -4.88f, -3.0f, -7.91f)
                reflectiveCurveToRelative(1.14f, -5.79f, 3.0f, -7.91f)
                verticalLineToRelative(2.16f)
                curveTo(4.5f, 6.66f, 4.84f, 7.0f, 5.25f, 7.0f)
                curveTo(5.66f, 7.0f, 6.0f, 6.66f, 6.0f, 6.25f)
                verticalLineTo(3.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineTo(1.75f)
                curveTo(1.34f, 2.0f, 1.0f, 2.34f, 1.0f, 2.75f)
                reflectiveCurveTo(1.34f, 3.5f, 1.75f, 3.5f)
                horizontalLineToRelative(1.27f)
                curveTo(1.13f, 5.82f, 0.0f, 8.78f, 0.0f, 12.0f)
                close()
                moveTo(8.83f, 19.1f)
                curveToRelative(-0.26f, -0.6f, 0.09f, -1.28f, 0.73f, -1.41f)
                lineToRelative(3.58f, -0.71f)
                lineTo(8.79f, 7.17f)
                curveToRelative(-0.34f, -0.76f, 0.0f, -1.64f, 0.76f, -1.98f)
                curveToRelative(0.76f, -0.34f, 1.64f, 0.0f, 1.98f, 0.76f)
                lineToRelative(2.43f, 5.49f)
                lineToRelative(0.84f, -0.37f)
                curveToRelative(0.28f, -0.13f, 0.59f, -0.18f, 0.9f, -0.17f)
                lineToRelative(4.56f, 0.21f)
                curveToRelative(0.86f, 0.04f, 1.6f, 0.63f, 1.83f, 1.45f)
                lineToRelative(1.23f, 4.33f)
                curveToRelative(0.27f, 0.96f, -0.2f, 1.97f, -1.11f, 2.37f)
                lineToRelative(-5.63f, 2.49f)
                curveToRelative(-0.48f, 0.21f, -1.26f, 0.33f, -1.76f, 0.14f)
                lineToRelative(-5.45f, -2.27f)
                curveTo(9.13f, 19.53f, 8.93f, 19.34f, 8.83f, 19.1f)
                close()
            }
        }
        return swipeVerticalCache!!
    }

private var swipeVerticalCache: ImageVector? = null

val ReikaiIcons.Star: ImageVector
    get() {
        if (starCache != null) {
            return starCache!!
        }
        starCache = materialIcon(name = "Reikai.Star") {
            materialPath {
                moveTo(12.0f, 17.27f)
                lineToRelative(4.15f, 2.51f)
                curveToRelative(0.76f, 0.46f, 1.69f, -0.22f, 1.49f, -1.08f)
                lineToRelative(-1.1f, -4.72f)
                lineToRelative(3.67f, -3.18f)
                curveToRelative(0.67f, -0.58f, 0.31f, -1.68f, -0.57f, -1.75f)
                lineToRelative(-4.83f, -0.41f)
                lineToRelative(-1.89f, -4.46f)
                curveToRelative(-0.34f, -0.81f, -1.5f, -0.81f, -1.84f, 0.0f)
                lineTo(9.19f, 8.63f)
                lineTo(4.36f, 9.04f)
                curveToRelative(-0.88f, 0.07f, -1.24f, 1.17f, -0.57f, 1.75f)
                lineToRelative(3.67f, 3.18f)
                lineToRelative(-1.1f, 4.72f)
                curveToRelative(-0.2f, 0.86f, 0.73f, 1.54f, 1.49f, 1.08f)
                lineTo(12.0f, 17.27f)
                close()
            }
        }
        return starCache!!
    }

private var starCache: ImageVector? = null

val ReikaiIcons.StarBorder: ImageVector
    get() {
        if (starBorderCache != null) {
            return starBorderCache!!
        }
        starBorderCache = materialIcon(name = "Reikai.StarBorder") {
            materialPath {
                moveTo(19.65f, 9.04f)
                lineToRelative(-4.84f, -0.42f)
                lineToRelative(-1.89f, -4.45f)
                curveToRelative(-0.34f, -0.81f, -1.5f, -0.81f, -1.84f, 0.0f)
                lineTo(9.19f, 8.63f)
                lineToRelative(-4.83f, 0.41f)
                curveToRelative(-0.88f, 0.07f, -1.24f, 1.17f, -0.57f, 1.75f)
                lineToRelative(3.67f, 3.18f)
                lineToRelative(-1.1f, 4.72f)
                curveToRelative(-0.2f, 0.86f, 0.73f, 1.54f, 1.49f, 1.08f)
                lineToRelative(4.15f, -2.5f)
                lineToRelative(4.15f, 2.51f)
                curveToRelative(0.76f, 0.46f, 1.69f, -0.22f, 1.49f, -1.08f)
                lineToRelative(-1.1f, -4.73f)
                lineToRelative(3.67f, -3.18f)
                curveToRelative(0.67f, -0.58f, 0.32f, -1.68f, -0.56f, -1.75f)
                close()
                moveTo(12.0f, 15.4f)
                lineToRelative(-3.76f, 2.27f)
                lineToRelative(1.0f, -4.28f)
                lineToRelative(-3.32f, -2.88f)
                lineToRelative(4.38f, -0.38f)
                lineTo(12.0f, 6.1f)
                lineToRelative(1.71f, 4.04f)
                lineToRelative(4.38f, 0.38f)
                lineToRelative(-3.32f, 2.88f)
                lineToRelative(1.0f, 4.28f)
                lineTo(12.0f, 15.4f)
                close()
            }
        }
        return starBorderCache!!
    }

private var starBorderCache: ImageVector? = null

val ReikaiIcons.StarHalf: ImageVector
    get() {
        if (starHalfCache != null) {
            return starHalfCache!!
        }
        starHalfCache = materialIcon(name = "Reikai.StarHalf", autoMirror = true) {
            materialPath {
                moveTo(19.65f, 9.04f)
                lineToRelative(-4.84f, -0.42f)
                lineToRelative(-1.89f, -4.45f)
                curveToRelative(-0.34f, -0.81f, -1.5f, -0.81f, -1.84f, 0.0f)
                lineTo(9.19f, 8.63f)
                lineToRelative(-4.83f, 0.41f)
                curveToRelative(-0.88f, 0.07f, -1.24f, 1.17f, -0.57f, 1.75f)
                lineToRelative(3.67f, 3.18f)
                lineToRelative(-1.1f, 4.72f)
                curveToRelative(-0.2f, 0.86f, 0.73f, 1.54f, 1.49f, 1.08f)
                lineToRelative(4.15f, -2.5f)
                lineToRelative(4.15f, 2.51f)
                curveToRelative(0.76f, 0.46f, 1.69f, -0.22f, 1.49f, -1.08f)
                lineToRelative(-1.1f, -4.73f)
                lineToRelative(3.67f, -3.18f)
                curveToRelative(0.67f, -0.58f, 0.32f, -1.68f, -0.56f, -1.75f)
                close()
                moveTo(12.0f, 15.4f)
                verticalLineTo(6.1f)
                lineToRelative(1.71f, 4.04f)
                lineToRelative(4.38f, 0.38f)
                lineToRelative(-3.32f, 2.88f)
                lineToRelative(1.0f, 4.28f)
                lineTo(12.0f, 15.4f)
                close()
            }
        }
        return starHalfCache!!
    }

private var starHalfCache: ImageVector? = null

val ReikaiIcons.UTurnRight: ImageVector
    get() {
        if (uTurnRightCache != null) {
            return uTurnRightCache!!
        }
        uTurnRightCache = materialIcon(name = "Reikai.UTurnRight") {
            materialPath {
                moveTo(20.29f, 12.29f)
                curveToRelative(-0.39f, -0.39f, -1.02f, -0.39f, -1.41f, 0.0f)
                lineTo(18.0f, 13.17f)
                verticalLineTo(9.0f)
                curveToRelative(0.0f, -3.31f, -2.69f, -6.0f, -6.0f, -6.0f)
                reflectiveCurveTo(6.0f, 5.69f, 6.0f, 9.0f)
                verticalLineToRelative(11.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                reflectiveCurveToRelative(1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineTo(9.0f)
                curveToRelative(0.0f, -2.21f, 1.79f, -4.0f, 4.0f, -4.0f)
                reflectiveCurveToRelative(4.0f, 1.79f, 4.0f, 4.0f)
                verticalLineToRelative(4.17f)
                lineToRelative(-0.88f, -0.88f)
                curveToRelative(-0.39f, -0.39f, -1.02f, -0.39f, -1.41f, 0.0f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.02f, 0.0f, 1.41f)
                lineToRelative(2.59f, 2.59f)
                curveToRelative(0.39f, 0.39f, 1.02f, 0.39f, 1.41f, 0.0f)
                lineToRelative(2.59f, -2.59f)
                curveTo(20.68f, 13.32f, 20.68f, 12.68f, 20.29f, 12.29f)
                close()
            }
        }
        return uTurnRightCache!!
    }

private var uTurnRightCache: ImageVector? = null

val ReikaiIcons.Bedtime: ImageVector
    get() {
        if (bedtimeCache != null) {
            return bedtimeCache!!
        }
        bedtimeCache = materialIcon(name = "Reikai.Bedtime") {
            materialPath {
                moveTo(11.65f, 3.46f)
                curveToRelative(0.27f, -0.71f, -0.36f, -1.45f, -1.12f, -1.34f)
                curveToRelative(-5.52f, 0.8f, -9.47f, 6.07f, -8.34f, 11.88f)
                curveToRelative(0.78f, 4.02f, 4.09f, 7.21f, 8.14f, 7.87f)
                curveToRelative(3.74f, 0.61f, 7.16f, -0.87f, 9.32f, -3.44f)
                curveToRelative(0.48f, -0.57f, 0.19f, -1.48f, -0.55f, -1.62f)
                curveTo(13.08f, 15.66f, 9.42f, 9.27f, 11.65f, 3.46f)
                close()
            }
        }
        return bedtimeCache!!
    }

private var bedtimeCache: ImageVector? = null

val ReikaiIcons.FastForward: ImageVector
    get() {
        if (fastForwardCache != null) {
            return fastForwardCache!!
        }
        fastForwardCache = materialIcon(name = "Reikai.FastForward") {
            materialPath {
                moveTo(5.58f, 16.89f)
                lineToRelative(5.77f, -4.07f)
                curveToRelative(0.56f, -0.4f, 0.56f, -1.24f, 0.0f, -1.63f)
                lineTo(5.58f, 7.11f)
                curveTo(4.91f, 6.65f, 4.0f, 7.12f, 4.0f, 7.93f)
                verticalLineToRelative(8.14f)
                curveToRelative(0.0f, 0.81f, 0.91f, 1.28f, 1.58f, 0.82f)
                close()
                moveTo(13.0f, 7.93f)
                verticalLineToRelative(8.14f)
                curveToRelative(0.0f, 0.81f, 0.91f, 1.28f, 1.58f, 0.82f)
                lineToRelative(5.77f, -4.07f)
                curveToRelative(0.56f, -0.4f, 0.56f, -1.24f, 0.0f, -1.63f)
                lineToRelative(-5.77f, -4.07f)
                curveToRelative(-0.67f, -0.47f, -1.58f, 0.0f, -1.58f, 0.81f)
                close()
            }
        }
        return fastForwardCache!!
    }

private var fastForwardCache: ImageVector? = null

val ReikaiIcons.FastRewind: ImageVector
    get() {
        if (fastRewindCache != null) {
            return fastRewindCache!!
        }
        fastRewindCache = materialIcon(name = "Reikai.FastRewind") {
            materialPath {
                moveTo(11.0f, 16.07f)
                lineTo(11.0f, 7.93f)
                curveToRelative(0.0f, -0.81f, -0.91f, -1.28f, -1.58f, -0.82f)
                lineToRelative(-5.77f, 4.07f)
                curveToRelative(-0.56f, 0.4f, -0.56f, 1.24f, 0.0f, 1.63f)
                lineToRelative(5.77f, 4.07f)
                curveToRelative(0.67f, 0.47f, 1.58f, 0.0f, 1.58f, -0.81f)
                close()
                moveTo(12.66f, 12.82f)
                lineToRelative(5.77f, 4.07f)
                curveToRelative(0.66f, 0.47f, 1.58f, -0.01f, 1.58f, -0.82f)
                lineTo(20.01f, 7.93f)
                curveToRelative(0.0f, -0.81f, -0.91f, -1.28f, -1.58f, -0.82f)
                lineToRelative(-5.77f, 4.07f)
                curveToRelative(-0.57f, 0.4f, -0.57f, 1.24f, 0.0f, 1.64f)
                close()
            }
        }
        return fastRewindCache!!
    }

private var fastRewindCache: ImageVector? = null

val ReikaiIcons.RecordVoiceOver: ImageVector
    get() {
        if (recordVoiceOverCache != null) {
            return recordVoiceOverCache!!
        }
        recordVoiceOverCache = materialIcon(name = "Reikai.RecordVoiceOver") {
            materialPath {
                moveTo(9.0f, 9.0f)
                moveToRelative(-4.0f, 0.0f)
                arcToRelative(4.0f, 4.0f, 0.0f, true, true, 8.0f, 0.0f)
                arcToRelative(4.0f, 4.0f, 0.0f, true, true, -8.0f, 0.0f)
            }
            materialPath {
                moveTo(9.0f, 15.0f)
                curveToRelative(-2.67f, 0.0f, -8.0f, 1.34f, -8.0f, 4.0f)
                verticalLineToRelative(1.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(-1.0f)
                curveToRelative(0.0f, -2.66f, -5.33f, -4.0f, -8.0f, -4.0f)
                close()
                moveTo(15.47f, 7.77f)
                curveToRelative(0.32f, 0.79f, 0.32f, 1.67f, 0.0f, 2.46f)
                curveToRelative(-0.19f, 0.47f, -0.11f, 1.0f, 0.25f, 1.36f)
                lineToRelative(0.03f, 0.03f)
                curveToRelative(0.58f, 0.58f, 1.57f, 0.46f, 1.95f, -0.27f)
                curveToRelative(0.76f, -1.45f, 0.76f, -3.15f, -0.02f, -4.66f)
                curveToRelative(-0.38f, -0.74f, -1.38f, -0.88f, -1.97f, -0.29f)
                lineToRelative(-0.01f, 0.01f)
                curveToRelative(-0.34f, 0.35f, -0.42f, 0.89f, -0.23f, 1.36f)
                close()
                moveTo(19.18f, 2.89f)
                curveToRelative(-0.4f, 0.4f, -0.46f, 1.02f, -0.13f, 1.48f)
                curveToRelative(1.97f, 2.74f, 1.96f, 6.41f, -0.03f, 9.25f)
                curveToRelative(-0.32f, 0.45f, -0.25f, 1.07f, 0.14f, 1.46f)
                lineToRelative(0.03f, 0.03f)
                curveToRelative(0.49f, 0.49f, 1.32f, 0.45f, 1.74f, -0.1f)
                curveToRelative(2.75f, -3.54f, 2.76f, -8.37f, 0.0f, -12.02f)
                curveToRelative(-0.42f, -0.55f, -1.26f, -0.59f, -1.75f, -0.1f)
                close()
            }
        }
        return recordVoiceOverCache!!
    }

private var recordVoiceOverCache: ImageVector? = null

val ReikaiIcons.VolumeUp: ImageVector
    get() {
        if (volumeUpCache != null) {
            return volumeUpCache!!
        }
        volumeUpCache = materialIcon(name = "Reikai.VolumeUp") {
            materialPath {
                moveTo(3.0f, 10.0f)
                verticalLineToRelative(4.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(3.0f)
                lineToRelative(3.29f, 3.29f)
                curveToRelative(0.63f, 0.63f, 1.71f, 0.18f, 1.71f, -0.71f)
                lineTo(12.0f, 6.41f)
                curveToRelative(0.0f, -0.89f, -1.08f, -1.34f, -1.71f, -0.71f)
                lineTo(7.0f, 9.0f)
                lineTo(4.0f, 9.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                close()
                moveTo(16.5f, 12.0f)
                curveToRelative(0.0f, -1.77f, -1.02f, -3.29f, -2.5f, -4.03f)
                verticalLineToRelative(8.05f)
                curveToRelative(1.48f, -0.73f, 2.5f, -2.25f, 2.5f, -4.02f)
                close()
                moveTo(14.0f, 4.45f)
                verticalLineToRelative(0.2f)
                curveToRelative(0.0f, 0.38f, 0.25f, 0.71f, 0.6f, 0.85f)
                curveTo(17.18f, 6.53f, 19.0f, 9.06f, 19.0f, 12.0f)
                reflectiveCurveToRelative(-1.82f, 5.47f, -4.4f, 6.5f)
                curveToRelative(-0.36f, 0.14f, -0.6f, 0.47f, -0.6f, 0.85f)
                verticalLineToRelative(0.2f)
                curveToRelative(0.0f, 0.63f, 0.63f, 1.07f, 1.21f, 0.85f)
                curveTo(18.6f, 19.11f, 21.0f, 15.84f, 21.0f, 12.0f)
                reflectiveCurveToRelative(-2.4f, -7.11f, -5.79f, -8.4f)
                curveToRelative(-0.58f, -0.23f, -1.21f, 0.22f, -1.21f, 0.85f)
                close()
            }
        }
        return volumeUpCache!!
    }

private var volumeUpCache: ImageVector? = null

val ReikaiIcons.TouchApp: ImageVector
    get() {
        if (touchAppCache != null) {
            return touchAppCache!!
        }
        touchAppCache = materialIcon(name = "Reikai.TouchApp") {
            materialPath {
                moveTo(8.79f, 9.24f)
                verticalLineTo(5.5f)
                curveToRelative(0.0f, -1.38f, 1.12f, -2.5f, 2.5f, -2.5f)
                reflectiveCurveToRelative(2.5f, 1.12f, 2.5f, 2.5f)
                verticalLineToRelative(3.74f)
                curveToRelative(1.21f, -0.81f, 2.0f, -2.18f, 2.0f, -3.74f)
                curveToRelative(0.0f, -2.49f, -2.01f, -4.5f, -4.5f, -4.5f)
                reflectiveCurveToRelative(-4.5f, 2.01f, -4.5f, 4.5f)
                curveTo(6.79f, 7.06f, 7.58f, 8.43f, 8.79f, 9.24f)
                close()
                moveTo(14.29f, 11.71f)
                curveToRelative(-0.28f, -0.14f, -0.58f, -0.21f, -0.89f, -0.21f)
                horizontalLineToRelative(-0.61f)
                verticalLineToRelative(-6.0f)
                curveToRelative(0.0f, -0.83f, -0.67f, -1.5f, -1.5f, -1.5f)
                reflectiveCurveToRelative(-1.5f, 0.67f, -1.5f, 1.5f)
                verticalLineToRelative(10.74f)
                lineToRelative(-3.44f, -0.72f)
                curveToRelative(-0.37f, -0.08f, -0.76f, 0.04f, -1.03f, 0.31f)
                curveToRelative(-0.43f, 0.44f, -0.43f, 1.14f, 0.0f, 1.58f)
                lineToRelative(4.01f, 4.01f)
                curveTo(9.71f, 21.79f, 10.22f, 22.0f, 10.75f, 22.0f)
                horizontalLineToRelative(6.1f)
                curveToRelative(1.0f, 0.0f, 1.84f, -0.73f, 1.98f, -1.72f)
                lineToRelative(0.63f, -4.47f)
                curveToRelative(0.12f, -0.85f, -0.32f, -1.69f, -1.09f, -2.07f)
                lineTo(14.29f, 11.71f)
                close()
            }
        }
        return touchAppCache!!
    }

private var touchAppCache: ImageVector? = null

val ReikaiIcons.Contrast: ImageVector
    get() {
        if (contrastCache != null) {
            return contrastCache!!
        }
        contrastCache = materialIcon(name = "Reikai.Contrast") {
            materialPath {
                moveTo(12.0f, 22.0f)
                curveToRelative(5.52f, 0.0f, 10.0f, -4.48f, 10.0f, -10.0f)
                reflectiveCurveTo(17.52f, 2.0f, 12.0f, 2.0f)
                reflectiveCurveTo(2.0f, 6.48f, 2.0f, 12.0f)
                reflectiveCurveTo(6.48f, 22.0f, 12.0f, 22.0f)
                close()
                moveTo(13.0f, 4.07f)
                curveToRelative(3.94f, 0.49f, 7.0f, 3.85f, 7.0f, 7.93f)
                reflectiveCurveToRelative(-3.05f, 7.44f, -7.0f, 7.93f)
                verticalLineTo(4.07f)
                close()
            }
        }
        return contrastCache!!
    }

private var contrastCache: ImageVector? = null

val ReikaiIcons.Remove: ImageVector
    get() {
        if (removeCache != null) {
            return removeCache!!
        }
        removeCache = materialIcon(name = "Reikai.Remove") {
            materialPath {
                moveTo(18.0f, 13.0f)
                horizontalLineTo(6.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                reflectiveCurveToRelative(0.45f, -1.0f, 1.0f, -1.0f)
                horizontalLineToRelative(12.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
                reflectiveCurveToRelative(-0.45f, 1.0f, -1.0f, 1.0f)
                close()
            }
        }
        return removeCache!!
    }

private var removeCache: ImageVector? = null
