package eu.kanade.presentation.browse.components

import android.util.DisplayMetrics
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil3.compose.AsyncImage
import eu.kanade.domain.source.model.icon
import eu.kanade.presentation.util.rememberResourceBitmapPainter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import exh.assets.EhAssets
import exh.assets.ehassets.EhLogo
import exh.source.PURURIN_SOURCE_ID
import exh.source.eHentaiSourceIds
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.source.model.Source
import tachiyomi.source.local.isLocal

private val defaultModifier = Modifier
    .height(40.dp)
    .aspectRatio(1f)

@Composable
fun SourceIcon(
    source: Source,
    modifier: Modifier = Modifier,
) {
    val icon = source.icon

    when {
        source.isStub && icon == null -> {
            Image(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.error),
                modifier = modifier.then(defaultModifier),
            )
        }
        icon != null -> {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = modifier.then(defaultModifier),
            )
        }
        // RK: the built-in E-Hentai / ExHentai sources ship no extension icon, so draw the EH mark
        //     on a light tile (the brand red reads on both themes) instead of the default placeholder.
        source.id in eHentaiSourceIds -> {
            Box(
                modifier = modifier
                    .then(defaultModifier)
                    // Match the transparent safe-zone margin baked into extension launcher icons, so
                    // the tile reads the same size as its neighbours instead of full-bleed.
                    .padding(5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    imageVector = EhAssets.EhLogo,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(0.72f),
                )
            }
        }
        // RK: the built-in Pururin source ships no extension icon; show its logo on the same tile
        //     treatment as E-Hentai so the two built-in adult sources read consistently.
        source.id == PURURIN_SOURCE_ID -> {
            Box(
                modifier = modifier
                    .then(defaultModifier)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.pururin_logo),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        source.isLocal() -> {
            Image(
                painter = painterResource(R.mipmap.ic_local_source),
                contentDescription = null,
                modifier = modifier.then(defaultModifier),
            )
        }
        else -> {
            Image(
                painter = painterResource(R.mipmap.ic_default_source),
                contentDescription = null,
                modifier = modifier.then(defaultModifier),
            )
        }
    }
}

@Composable
fun ExtensionIcon(
    extension: Extension,
    modifier: Modifier = Modifier,
    density: Int = DisplayMetrics.DENSITY_DEFAULT,
) {
    when (extension) {
        is Extension.Available -> {
            AsyncImage(
                model = extension.iconUrl,
                contentDescription = null,
                placeholder = ColorPainter(Color(0x1F888888)),
                error = rememberResourceBitmapPainter(id = R.drawable.cover_error),
                modifier = modifier
                    .clip(MaterialTheme.shapes.extraSmall),
            )
        }
        is Extension.Installed -> {
            val icon by extension.getIcon(density)
            when (icon) {
                Result.Loading -> Box(modifier = modifier)
                is Result.Success -> Image(
                    bitmap = (icon as Result.Success<ImageBitmap>).value,
                    contentDescription = null,
                    modifier = modifier,
                )
                Result.Error -> Image(
                    bitmap = ImageBitmap.imageResource(id = R.mipmap.ic_default_source),
                    contentDescription = null,
                    modifier = modifier,
                )
            }
        }
        is Extension.Untrusted -> Image(
            imageVector = Icons.Filled.Dangerous,
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.error),
            modifier = modifier.then(defaultModifier),
        )
    }
}

@Composable
private fun Extension.getIcon(density: Int = DisplayMetrics.DENSITY_DEFAULT): State<Result<ImageBitmap>> {
    val context = LocalContext.current
    return produceState<Result<ImageBitmap>>(initialValue = Result.Loading, this) {
        withIOContext {
            value = try {
                val appInfo = ExtensionLoader.getExtensionPackageInfoFromPkgName(context, pkgName)!!.applicationInfo!!
                val appResources = context.packageManager.getResourcesForApplication(appInfo)
                Result.Success(
                    appResources.getDrawableForDensity(appInfo.icon, density, null)!!
                        .toBitmap()
                        .asImageBitmap(),
                )
            } catch (e: Exception) {
                Result.Error
            }
        }
    }
}

sealed class Result<out T> {
    data object Loading : Result<Nothing>()
    data object Error : Result<Nothing>()
    data class Success<out T>(val value: T) : Result<T>()
}
