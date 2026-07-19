package reikai.presentation.details

import android.content.Context
import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.size.Size
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import eu.kanade.tachiyomi.util.system.toShareIntent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream

/**
 * Shared backing for the full-cover dialog, holding the save / share / set-custom / delete-custom logic
 * once for both content types (the twin of Mihon's per-type cover models). Subclasses supply only the
 * five per-type seams: the entry subscription, its coil model + save name, the custom-cover check, and
 * the two custom-cover writes (each keyed in its own id space, manga positive and novel negated).
 */
abstract class EntryCoverScreenModel<T : Any>(
    private val imageSaver: ImageSaver = Injekt.get(),
    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
) : ScreenModel {

    /** The reactive entry the cover belongs to; emits null until loaded. */
    protected abstract suspend fun subscribe(): Flow<T?>

    /** The coil model fed to `ImageRequest.data` (a `Manga`, or a `NovelCover`). */
    protected abstract fun coilModel(entry: T): Any

    /** Filename for a saved or shared cover. */
    protected abstract fun coverName(entry: T): String

    /** True when the entry has a user-set custom cover (drives the edit/delete dropdown). */
    abstract fun hasCustomCover(): Boolean

    /** Persist a picked image as the custom cover and bump `coverLastModified`. */
    protected abstract suspend fun persistCustomCover(entry: T, stream: InputStream)

    /** Remove the custom cover and bump `coverLastModified`. */
    protected abstract suspend fun removeCustomCover(entry: T)

    /**
     * The loaded entry. Collection starts lazily on the first subscriber, never from the base constructor:
     * an init-block collect would call the overridden [subscribe] (which reads the subclass's injected deps)
     * while the super constructor is still running, before those deps are assigned, so they would be null.
     */
    val entry: StateFlow<T?> = flow { emitAll(subscribe()) }
        .stateIn(screenModelScope, SharingStarted.Lazily, null)

    /** The loaded entry's coil model, so the shared dialog can render the cover without knowing the type. */
    val coverModel: StateFlow<Any?> = entry
        .map { it?.let(::coilModel) }
        .stateIn(screenModelScope, SharingStarted.Lazily, null)

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
        val entry = entry.value ?: return null
        val request = ImageRequest.Builder(context)
            .data(coilModel(entry))
            .size(Size.ORIGINAL)
            .build()

        return withIOContext {
            val result = context.imageLoader.execute(request).image?.asDrawable(context.resources)
            val bitmap = result?.getBitmapOrNull() ?: return@withIOContext null
            imageSaver.save(
                Image.Cover(
                    bitmap = bitmap,
                    name = coverName(entry),
                    location = if (temp) Location.Cache else Location.Pictures.create(),
                ),
            )
        }
    }

    fun editCover(context: Context, data: Uri) {
        val entry = entry.value ?: return
        screenModelScope.launchIO {
            try {
                context.contentResolver.openInputStream(data)?.use { input ->
                    persistCustomCover(entry, input)
                }
                notifyCoverUpdated(context)
            } catch (e: Exception) {
                notifyFailed(context, e)
            }
        }
    }

    fun deleteCustomCover(context: Context) {
        val entry = entry.value ?: return
        screenModelScope.launchIO {
            try {
                removeCustomCover(entry)
                notifyCoverUpdated(context)
            } catch (e: Exception) {
                notifyFailed(context, e)
            }
        }
    }

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
