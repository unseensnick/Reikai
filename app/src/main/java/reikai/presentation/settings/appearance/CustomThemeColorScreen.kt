package reikai.presentation.settings.appearance

import android.app.Activity
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.github.skydoves.colorpicker.compose.AlphaTile
import com.github.skydoves.colorpicker.compose.BrightnessSlider
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import reikai.presentation.components.toHexRgb
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

/**
 * Picks the seed color the Custom theme is generated from. Applying it also selects the Custom
 * theme and recreates the activity, as the theme picker does. From Kuukiyomi via Komikku.
 */
// no ViewModel: two preference writes on tap, no async state.
class CustomThemeColorScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val uiPreferences = remember { context.appGraph.uiPreferences }
        val initialColor = remember { Color(uiPreferences.customThemeSeed.get()) }
        val controller = rememberColorPickerController()
        val scope = rememberCoroutineScope()
        val scrollState = rememberScrollState()
        var selectedColor by remember { mutableStateOf(initialColor) }
        val wheelBitmap = rememberWheelBitmap()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.pref_custom_theme_color),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .padding(contentPadding)
                    .padding(horizontal = MaterialTheme.padding.large)
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HsvColorPicker(
                    modifier = Modifier
                        .padding(vertical = MaterialTheme.padding.small)
                        .size(300.dp),
                    controller = controller,
                    wheelImageBitmap = wheelBitmap,
                    initialColor = initialColor,
                    onColorChanged = { selectedColor = it.color },
                )
                BrightnessSlider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MaterialTheme.padding.small)
                        .height(24.dp),
                    controller = controller,
                    borderRadius = 12.dp,
                    wheelImageBitmap = wheelBitmap,
                    initialColor = initialColor,
                )
                Row(
                    modifier = Modifier.padding(MaterialTheme.padding.small),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.large),
                ) {
                    ColorSwatch(initialColor) { AlphaTile(it, selectedColor = initialColor) }
                    ColorSwatch(selectedColor) { AlphaTile(it, controller = controller) }
                }
                Button(
                    onClick = {
                        uiPreferences.customThemeSeed.set(selectedColor.toArgb())
                        uiPreferences.appTheme.set(AppTheme.CUSTOM)
                        (context as? Activity)?.let { ActivityCompat.recreate(it) }
                    },
                    modifier = Modifier
                        .padding(vertical = MaterialTheme.padding.medium)
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Text(text = stringResource(MR.strings.action_apply))
                }

                palettes.forEach { (title, colors) ->
                    Text(
                        text = stringResource(title),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = MaterialTheme.padding.medium),
                    )
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = MaterialTheme.padding.small),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        colors.forEach { color ->
                            Box(
                                modifier = Modifier
                                    .background(color = color, shape = MaterialTheme.shapes.small)
                                    .height(48.dp)
                                    .aspectRatio(1f)
                                    .clickable(role = Role.Button) {
                                        controller.selectByColor(color, true)
                                        scope.launch { scrollState.animateScrollTo(0) }
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorSwatch(color: Color, tile: @Composable (Modifier) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = color.toArgb().toHexRgb(),
            style = MaterialTheme.typography.labelMedium.copy(color = color),
        )
        tile(Modifier.size(64.dp).clip(MaterialTheme.shapes.small))
    }
}

/** The wheel's selector ring, a white circle outline. */
@Composable
private fun rememberWheelBitmap() = with(LocalDensity.current) {
    val size = 20.dp.toPx().roundToInt()
    val stroke = 2.dp.toPx()
    remember(size, stroke) {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = stroke
            isAntiAlias = true
        }
        android.graphics.Canvas(bitmap).drawCircle(size / 2f, size / 2f, size / 2f - stroke, paint)
        bitmap.asImageBitmap()
    }
}

private val palettes: List<Pair<StringResource, List<Color>>> = listOf(
    MR.strings.custom_theme_palette_sunset to colors(0xFF3E9CBF, 0xFFA7ECF2, 0xFFF2C43D, 0xFFF17C37, 0xFFF26D50),
    MR.strings.custom_theme_palette_outrun to colors(0xFFFFFC40, 0xFFFABA61, 0xFFFF8172, 0xFFFF2FA9, 0xFF3A579A),
    MR.strings.custom_theme_palette_raspberry to colors(0xFF730517, 0xFFF44560, 0xFF44D1DF, 0xFF32A4A7, 0xFF1E7069),
    MR.strings.custom_theme_palette_cathode to colors(0xFFA8216B, 0xFFF1184C, 0xFFF36943, 0xFFF7DC66, 0xFF2E9599),
    MR.strings.custom_theme_palette_bubblegum to colors(0xFFF8CD82, 0xFFF65B74, 0xFFF72078, 0xFF23B0BD, 0xFF0DF7DB),
    MR.strings.custom_theme_palette_springfield to
        colors(0xFF89D1DC, 0xFFF89CFA, 0xFFC386F1, 0xFFF0D689, 0xFFAFF28B),
    MR.strings.custom_theme_palette_spectral to colors(0xFF471337, 0xFFB13254, 0xFFFF5349, 0xFFFF7249, 0xFFFF9248),
    MR.strings.custom_theme_palette_night_at_the_beach to
        colors(0xFF262335, 0xFF503C52, 0xFF9F6C66, 0xFFD4896A, 0xFFFFBB6C),
    MR.strings.custom_theme_palette_casual to colors(0xFF4F6A8F, 0xFF88A2BC, 0xFFF0DBB0, 0xFFEFB680, 0xFFD99477),
    MR.strings.custom_theme_palette_patagonia to colors(0xFF202E32, 0xFF85937A, 0xFF586C5C, 0xFFA9AF90, 0xFFDFDCB9),
    MR.strings.custom_theme_palette_lcd to colors(0xFF0F370E, 0xFF30622F, 0xFF8BAD0D, 0xFF9BBC0E),
    MR.strings.custom_theme_palette_pop to colors(0xFF00FF3F, 0xFF35B5FF, 0xFFFF479C, 0xFFFFFB38),
    MR.strings.custom_theme_palette_pico to colors(
        0xFF1D2B53, 0xFF7E2453, 0xFF008751, 0xFFAB5236, 0xFF5F574E, 0xFFC2C3C7, 0xFFFF004D,
        0xFFFFA300, 0xFFFFEC27, 0xFF00E536, 0xFF29AEFF, 0xFF83769C, 0xFFFF77A8, 0xFFFFCDAA,
    ),
)

private fun colors(vararg argb: Long) = argb.map { Color(it) }
