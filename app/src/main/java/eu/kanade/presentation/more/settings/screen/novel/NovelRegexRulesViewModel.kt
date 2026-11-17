package eu.kanade.presentation.more.settings.screen.novel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.yield
import reikai.domain.novel.NovelPreferences
import reikai.novel.content.NovelRegexReplacement
import reikai.novel.content.NovelRegexReplacements
import reikai.novel.content.NovelRegexRules
import reikai.novel.content.NovelStoredToggleList
import reikai.util.runCatchingCancellable
import kotlin.time.Duration.Companion.milliseconds

/** What [rule] does to [sample], or why it cannot say: the pattern does not compile, or ran too long. */
@Immutable
data class NovelRegexPreview(val rule: NovelRegexReplacement, val sample: String, val result: Result<String>)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class NovelRegexRulesViewModel(
    novelPreferences: NovelPreferences,
) : NovelToggleListViewModel<NovelRegexReplacement>(
    NovelStoredToggleList(novelPreferences.readerRegexReplacements(), NovelRegexRules),
) {

    private val asked = MutableStateFlow<Pair<NovelRegexReplacement, String>?>(null)

    /**
     * The latest preview asked for, run through the same kernel the reader uses, so the answer here cannot
     * disagree with what a chapter gets. Off the main thread and settled before it runs, because the editor
     * asks on every keystroke and each slow run leaves a match behind that cannot be stopped.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val preview: StateFlow<NovelRegexPreview?> = asked.filterNotNull()
        .debounce(PREVIEW_SETTLE)
        .mapLatest { (rule, sample) -> NovelRegexPreview(rule, sample, run(rule, sample)) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    fun askPreview(rule: NovelRegexReplacement, sample: String) {
        asked.value = rule to sample
    }

    private suspend fun run(rule: NovelRegexReplacement, sample: String): Result<String> {
        // mapLatest starts this undispatched, so a run that blocks before suspending holds back the next
        // edit, which could then never cancel it. Suspending first frees mapLatest to take that edit.
        yield()
        val job = currentCoroutineContext().job
        // A run a newer edit cancelled must end there, not answer with the cancellation as the pattern's error.
        return runCatchingCancellable {
            val compiled = NovelRegexReplacements.compile(rule)
            previewRun.applyOrNull(compiled, sample, isActive = { job.isActive })
                ?: throw NovelRegexReplacements.TooSlowException()
        }
    }

    private companion object {
        val PREVIEW_SETTLE = 250.milliseconds

        /** A short budget, not the reader's, and a memo of its own so a preview never skips a reader rule. */
        val previewRun = NovelRegexReplacements.BudgetedRun(500.milliseconds)
    }
}
