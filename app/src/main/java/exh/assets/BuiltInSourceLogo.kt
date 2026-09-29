package exh.assets

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import eu.kanade.tachiyomi.R
import exh.assets.ehassets.EhLogo
import exh.source.NHENTAI_NET_SOURCE_ID
import exh.source.PURURIN_SOURCE_ID
import exh.source.eHentaiSourceIds

/**
 * The bundled logo a built-in adult source draws, since it ships no extension icon. Each surface keeps
 * its own geometry; a tiled logo goes on a white tile so its brand colours read on both themes, and an
 * untiled one already carries a dark backdrop of its own.
 */
enum class BuiltInSourceLogo(val isTiled: Boolean) {
    EHENTAI(isTiled = true),
    PURURIN(isTiled = true),
    NHENTAI(isTiled = false),
}

fun builtInSourceLogo(sourceId: Long): BuiltInSourceLogo? = when (sourceId) {
    in eHentaiSourceIds -> BuiltInSourceLogo.EHENTAI
    PURURIN_SOURCE_ID -> BuiltInSourceLogo.PURURIN
    NHENTAI_NET_SOURCE_ID -> BuiltInSourceLogo.NHENTAI
    else -> null
}

@Composable
fun BuiltInSourceLogo.painter(): Painter = when (this) {
    BuiltInSourceLogo.EHENTAI -> rememberVectorPainter(EhAssets.EhLogo)
    BuiltInSourceLogo.PURURIN -> painterResource(R.drawable.pururin_logo)
    BuiltInSourceLogo.NHENTAI -> painterResource(R.drawable.nhentai_logo)
}
