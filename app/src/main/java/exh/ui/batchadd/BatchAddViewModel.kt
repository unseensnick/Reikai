package exh.ui.batchadd

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import exh.GalleryAddEvent
import exh.GalleryAdder
import exh.source.ExhPreferences
import exh.util.trimOrNull
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class BatchAddViewModel(
    private val exhPreferences: ExhPreferences,
    // A Provider, so the adder (and the source manager behind it) is still only built when a batch
    // actually runs, as the `by lazy` did before.
    private val galleryAdderProvider: Provider<GalleryAdder>,
) : ViewModel() {

    val state: StateFlow<BatchAddState>
        field = MutableStateFlow<BatchAddState>(BatchAddState())
    private val galleryAdder by lazy { galleryAdderProvider() }

    fun addGalleries(context: Context) {
        val galleries = state.value.galleries
        // Check text box has content
        if (galleries.isBlank()) {
            state.update { it.copy(dialog = Dialog.NoGalleriesSpecified) }
            return
        }

        addGalleries(context, galleries)
    }

    private fun addGalleries(context: Context, galleries: String) {
        val splitGalleries = if (ehVisitedRegex.containsMatchIn(galleries)) {
            val url = if (exhPreferences.enableExhentai().get()) {
                "https://exhentai.org/g/"
            } else {
                "https://e-hentai.org/g/"
            }
            ehVisitedRegex.findAll(galleries).map { galleryKeys ->
                val linkParts = galleryKeys.value.split(".")
                url + linkParts[0] + "/" + linkParts[1].replace(":", "")
            }.toList()
        } else {
            galleries.split("\n")
                .mapNotNull(String::trimOrNull)
        }

        state.update { state ->
            state.copy(
                progress = 0,
                progressTotal = splitGalleries.size,
                state = State.PROGRESS,
            )
        }

        val handler = CoroutineExceptionHandler { _, throwable ->
            logcat(LogPriority.ERROR, throwable) { "Batch add error" }
        }

        viewModelScope.launch(Dispatchers.IO + handler) {
            val succeeded = mutableListOf<String>()
            val failed = mutableListOf<String>()

            splitGalleries.forEachIndexed { i, s ->
                ensureActive()
                val result = withIOContext {
                    galleryAdder.addGallery(
                        context = context,
                        url = s,
                        fav = true,
                        retry = 2,
                    )
                }
                if (result is GalleryAddEvent.Success) {
                    succeeded.add(s)
                } else {
                    failed.add(s)
                }
                val message = when (result) {
                    is GalleryAddEvent.Success -> context.stringResource(MR.strings.batch_add_ok)
                    is GalleryAddEvent.Fail -> context.stringResource(MR.strings.batch_add_error)
                } + " " + result.logMessage
                state.update { state ->
                    state.copy(
                        progress = i + 1,
                        events = state.events + BatchAddEvent(message, (result as? GalleryAddEvent.Success)?.manga),
                    )
                }
            }

            // Show report
            val summary = context.stringResource(MR.strings.batch_add_summary, succeeded.size, failed.size)
            state.update { state ->
                state.copy(
                    events = state.events + BatchAddEvent(summary),
                )
            }
        }
    }

    fun finish() {
        state.update { state ->
            state.copy(
                progressTotal = 0,
                progress = 0,
                galleries = "",
                state = State.INPUT,
                events = emptyList(),
            )
        }
    }

    fun updateGalleries(galleries: String) {
        state.update { it.copy(galleries = galleries) }
    }

    fun dismissDialog() {
        state.update { it.copy(dialog = null) }
    }

    enum class State {
        INPUT,
        PROGRESS,
    }

    sealed class Dialog {
        data object NoGalleriesSpecified : Dialog()
    }

    companion object {
        val ehVisitedRegex = """[0-9]*?\.[a-z0-9]*?:""".toRegex()
    }
}

data class BatchAddEvent(
    val message: String,
    val manga: Manga? = null,
)

data class BatchAddState(
    val progressTotal: Int = 0,
    val progress: Int = 0,
    val galleries: String = "",
    val state: BatchAddViewModel.State = BatchAddViewModel.State.INPUT,
    val events: List<BatchAddEvent> = emptyList(),
    val dialog: BatchAddViewModel.Dialog? = null,
)
