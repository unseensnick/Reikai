package reikai.presentation.browse.components

import androidx.compose.runtime.Composable
import reikai.novel.source.NovelExtensionFormat
import tachiyomi.presentation.core.i18n.stringResource

/** A source's line under its name: its language, then how it is packaged, each when there is one. */
fun sourceDetail(language: String?, format: String?): String? =
    listOfNotNull(language?.takeIf { it.isNotBlank() }, format).joinToString(" • ").ifEmpty { null }

/** A novel source's packaging, named only where [showsFormat] says its list holds more than one. */
@Composable
fun formatLabel(format: NovelExtensionFormat?, showsFormat: Boolean): String? =
    format?.takeIf { showsFormat }?.let { stringResource(it.label) }
