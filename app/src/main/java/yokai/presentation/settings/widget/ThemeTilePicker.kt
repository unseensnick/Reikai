package yokai.presentation.settings.widget

import android.app.Activity
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.preference.Preference
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.util.system.Themes
import eu.kanade.tachiyomi.util.system.appDelegateNightMode
import eu.kanade.tachiyomi.util.system.getResourceColor
import com.google.android.material.color.DynamicColors
import uy.kohesive.injekt.injectLazy
import android.R as AR

/**
 * Theme tile picker. Compose port of the legacy `ThemePreference` widget
 * (eu.kanade.tachiyomi.ui.setting.ThemePreference, layout/theme_item.xml) so the new
 * Appearance screen carries the same visual tile language users expect.
 *
 * Two horizontal rails: one for light themes, one for dark themes. Each tile is a 110dp wide
 * card with a mini-preview of the theme's actual chrome (toolbar bar, content area, accent
 * dot, bottom-nav dots) using colors read out of the theme's XML style via
 * [Context.createConfigurationContext] + [setTheme]. Selecting a tile writes the corresponding
 * lightTheme / darkTheme pref AND triggers Activity.recreate() so the change applies instantly,
 * matching the legacy behavior.
 *
 * The Monet theme is hidden on devices without dynamic color support (mirrors the legacy
 * filter in [eu.kanade.tachiyomi.ui.setting.ThemePreference] init).
 */
@Composable
fun ThemeTilePicker(
    pref: Preference<Themes>,
    isDark: Boolean,
) {
    val context = LocalContext.current
    val preferences: PreferencesHelper by injectLazy()
    val selected by pref.collectAsState()
    val amoled by preferences.themeDarkAmoled().collectAsState()

    val themes = remember(isDark) {
        val supportsDynamic = DynamicColors.isDynamicColorAvailable()
        Themes.entries.filter {
            val matchesMode = if (isDark) it.isDarkTheme || it.followsSystem else !it.isDarkTheme || it.followsSystem
            val monetGate = it.styleRes != R.style.Theme_Tachiyomi_Monet || supportsDynamic
            matchesMode && monetGate
        }
    }

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(themes, key = { it.name }) { theme ->
            ThemeTile(
                theme = theme,
                isDark = isDark,
                amoled = isDark && amoled,
                isSelected = selected == theme,
                onClick = {
                    pref.set(theme)
                    // The legacy widget also synchronises night-mode against the rail you tapped
                    // so picking a dark tile while in light mode flips to dark immediately. The
                    // recreate happens regardless of whether the night mode actually changed.
                    val targetMode = if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                    if (preferences.nightMode().get() != AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) {
                        preferences.nightMode().set(targetMode)
                    }
                    (context as? Activity)?.recreate()
                },
            )
        }
    }
}

@Composable
private fun ThemeTile(
    theme: Themes,
    isDark: Boolean,
    amoled: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val colors = remember(theme, isDark, amoled) {
        val cfg = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (isDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val themed = context.createConfigurationContext(cfg).apply { setTheme(theme.styleRes) }
        ThemeColors(
            background = if (amoled) Color.Black else Color(themed.getResourceColor(R.attr.background)),
            surface = Color(themed.getResourceColor(R.attr.colorSurface)),
            appBarText = Color(themed.getResourceColor(R.attr.actionBarTintColor)),
            primaryText = Color(themed.getResourceColor(AR.attr.textColorPrimary)),
            secondaryText = Color(themed.getResourceColor(AR.attr.textColorSecondary)),
            colorSecondary = Color(themed.getResourceColor(R.attr.colorSecondary)),
            bottomBar = Color(themed.getResourceColor(R.attr.colorPrimaryVariant)),
            inactiveTab = Color(themed.getResourceColor(R.attr.tabBarIconInactive)),
            activeTab = Color(themed.getResourceColor(R.attr.tabBarIconColor)),
        )
    }

    val themeMatchesAppMode = remember(isDark, context) {
        // When the app is currently in the OTHER mode the selection check dims so the user
        // remembers the tile is locked-in but not what they're looking at right now.
        if (context.appDelegateNightMode() == AppCompatDelegate.MODE_NIGHT_YES) isDark else !isDark
    }
    val selectionAlpha = if (themeMatchesAppMode) 1f else 0.5f

    Column(
        modifier = Modifier
            .width(110.dp)
            .padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .padding(6.dp)
                .border(
                    width = if (isSelected) 2.dp else 0.dp,
                    color = if (isSelected) colors.colorSecondary.copy(alpha = selectionAlpha) else Color.Transparent,
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(2.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.background)
                .clickable(onClick = onClick),
        ) {
            ThemeMiniPreview(colors)
            if (isSelected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = colors.colorSecondary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(20.dp)
                        .alpha(selectionAlpha),
                )
            }
        }
        Text(
            text = stringResource(if (isDark) theme.darkNameRes else theme.nameRes),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(top = 2.dp)
                .fillMaxWidth(),
        )
    }
}

/**
 * Mini-preview chrome. Mirrors [eu.kanade.tachiyomi.databinding.ThemeItemBinding]: toolbar
 * stripe at the top with a single "icon" rectangle, a hero block, primary text stripe with an
 * accent oval, a secondary text stripe split into two segments, and a bottom bar with three
 * nav dots (inactive, active, inactive).
 */
@Composable
private fun ThemeMiniPreview(colors: ThemeColors) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Toolbar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .background(colors.surface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.appBarText),
            )
        }
        // Hero block
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.primaryText.copy(alpha = 0.30f)),
        )
        // Primary text + accent oval
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .height(6.dp)
                    .weight(1f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.primaryText),
            )
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(width = 16.dp, height = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.colorSecondary),
            )
        }
        // Secondary text (two segments)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .height(6.dp)
                    .weight(1f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.secondaryText),
            )
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .height(6.dp)
                    .width(20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.secondaryText),
            )
        }
        Spacer(Modifier.weight(1f))
        // Bottom bar with 3 nav dots
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .background(colors.bottomBar)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(colors.inactiveTab))
            Box(Modifier.size(8.dp).clip(CircleShape).background(colors.activeTab))
            Box(Modifier.size(8.dp).clip(CircleShape).background(colors.inactiveTab))
        }
    }
}

/** Cached colors extracted from one theme's XML style at one night-mode setting. */
private data class ThemeColors(
    val background: Color,
    val surface: Color,
    val appBarText: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val colorSecondary: Color,
    val bottomBar: Color,
    val inactiveTab: Color,
    val activeTab: Color,
)
