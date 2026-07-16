package reikai.presentation.novel.details

import android.content.Context
import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.size.Size
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import eu.kanade.tachiyomi.util.system.toShareIntent
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import reikai.data.coil.NovelCover
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.UpdateNovel
import reikai.domain.novel.model.Novel
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Backs the novel full-cover dialog, the novel twin of
 * [eu.kanade.tachiyomi.ui.manga.MangaCoverScreenModel]. Save / share build the image off the shared
 * [ImageSaver]; edit / delete write the custom cover into [CoverCache] keyed by the negated novel id
 * (so it can't collide with a same-id manga) and bump `coverLastModified` to bust the coil cache.
 *
 * Subscribes by url + source (there is no by-id novel flow) so the cover refreshes after an edit.
 */
class NovelCoverScreenModel(
    private val novelUrl: String,
    private val novelSource: String,
    private val site: String?,
    private val novelRepo: NovelRepository = Injekt.get(),
    private val updateNovel: UpdateNovel = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val imageSaver: ImageSaver = Injekt.get(),

    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
) : StateScreenModel<Novel?>(null) {

    init {
        screenModelScope.launchIO {
            novelRepo.getByUrlAndSourceAsFlow(novelUrl, novelSource)
                .collect { novel -> mutableState.update { novel } }
        }
    }

    /** True when the novel has a user-set custom cover (drives the edit/delete dropdown). */
    fun hasCustomCover(): Boolean {
        val novel = state.value ?: return false
        return coverCache.getCustomCoverFile(-novel.id).exists()
    }

    fun saveCover(context: Context) {
        screenModelScope.launch {
            try {
                saveCoverInternal(context, temp = false)
                snackbarHostState.showSnackbar(context.stringResource(MR.strings.cover_saved), withDismissAction = true)
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e)
                snackbarHostState.showSnackbar(
                    context.stringResource(MR.strings.error_saving_cover),
                    withDismissAction = true,
                )
            }
        }
    }

    fun shareCover(context: Context) {
        screenModelScope.launch {
            try {
                val uri = saveCoverInternal(context, temp = true) ?: return@launch
                withUIContext { context.startActivity(uri.toShareIntent(context)) }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e)
                snackbarHostState.showSnackbar(
                    context.stringResource(MR.strings.error_sharing_cover),
                    withDismissAction = true,
                )
            }
        }
    }

    private suspend fun saveCoverInternal(context: Context, temp: Boolean): Uri? {
        val novel = state.value ?: return null
        val req = ImageRequest.Builder(context)
            .data(novel.toNovelCover())
            .size(Size.ORIGINAL)
            .build()

        return withIOContext {
            val result = context.imageLoader.execute(req).image?.asDrawable(context.resources)
            val bitmap = result?.getBitmapOrNull() ?: return@withIOContext null
            imageSaver.save(
                Image.Cover(
                    bitmap = bitmap,
                    name = novel.title,
                    location = if (temp) Location.Cache else Location.Pictures.create(),
                ),
            )
        }
    }

    /** Set a custom cover from a local image; mirrors the manga `editCover` favorite branch. */
    fun editCover(context: Context, data: Uri) {
        val novel = state.value ?: return
        screenModelScope.launchIO {
            try {
                context.contentResolver.openInputStream(data)?.use { input ->
                    coverCache.getCustomCoverFile(-novel.id).outputStream().use { output -> input.copyTo(output) }
                }
                updateNovel.awaitUpdateCoverLastModified(novel.id)
                notifyCoverUpdated(context)
            } catch (e: Exception) {
                notifyFailed(context, e)
            }
        }
    }

    fun deleteCustomCover(context: Context) {
        val novel = state.value ?: return
        screenModelScope.launchIO {
            try {
                coverCache.deleteCustomCover(-novel.id)
                updateNovel.awaitUpdateCoverLastModified(novel.id)
                notifyCoverUpdated(context)
            } catch (e: Exception) {
                notifyFailed(context, e)
            }
        }
    }

    private fun Novel.toNovelCover() = NovelCover(
        url = thumbnailUrl,
        site = site,
        isNovelFavorite = favorite,
        lastModified = coverLastModified,
        novelId = id,
    )

    private fun notifyCoverUpdated(context: Context) {
        screenModelScope.launch {
            snackbarHostState.showSnackbar(context.stringResource(MR.strings.cover_updated), withDismissAction = true)
        }
    }

    private fun notifyFailed(context: Context, e: Throwable) {
        screenModelScope.launch {
            snackbarHostState.showSnackbar(
                context.stringResource(MR.strings.notification_cover_update_failed),
                withDismissAction = true,
            )
            logcat(LogPriority.ERROR, e)
        }
    }
}
