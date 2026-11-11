package eu.kanade.domain.ui.model

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

enum class AppTheme(val titleRes: StringResource?) {
    DEFAULT(MR.strings.label_default),
    MONET(MR.strings.theme_monet),
    CUSTOM(MR.strings.theme_custom), // RK: from Kuukiyomi via Komikku
    CATPPUCCIN(MR.strings.theme_catppuccin),
    GREEN_APPLE(MR.strings.theme_greenapple),
    LAVENDER(MR.strings.theme_lavender),
    MIDNIGHT_DUSK(MR.strings.theme_midnightdusk),
    NORD(MR.strings.theme_nord),
    STRAWBERRY_DAIQUIRI(MR.strings.theme_strawberrydaiquiri),
    TAKO(MR.strings.theme_tako),
    TEALTURQUOISE(MR.strings.theme_tealturquoise),
    TIDAL_WAVE(MR.strings.theme_tidalwave),
    YINYANG(MR.strings.theme_yinyang),
    YOTSUBA(MR.strings.theme_yotsuba),
    TOKYONIGHT(MR.strings.theme_tokyonight),
    MONOCHROME(MR.strings.theme_monochrome),

    // RK --> from Aniyomi via Komikku
    COTTONCANDY(MR.strings.theme_cottoncandy),
    MOCHA(MR.strings.theme_mocha),
    CLOUDFLARE(MR.strings.theme_cloudflare),
    DOOM(MR.strings.theme_doom),
    MATRIX(MR.strings.theme_matrix),
    SAPPHIRE(MR.strings.theme_sapphire),

    // From Yōkai, which Reikai was built on before Mihon
    SPRING_AND_DUSK(MR.strings.theme_springanddusk),
    STRAWBERRIES(MR.strings.theme_strawberries),
    TEAL_AND_SAPPHIRE(MR.strings.theme_tealandsapphire),
    LIME(MR.strings.theme_lime),
    DOKI(MR.strings.theme_doki),
    // RK <--

    // Deprecated
    DARK_BLUE(null),
    HOT_PINK(null),
    BLUE(null),
}
