package yokai.presentation.extension.repo

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.util.system.launchIO
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import yokai.domain.novel.NovelPreferences
import yokai.i18n.MR
import yokai.novel.install.LnPluginInstaller

/**
 * Novel-side parallel of [ExtensionRepoScreenModel] for the Phase 8 follow-up tabbed
 * `ExtensionRepoScreen`. Manages the set of plugin registry URLs the user has added
 * (Browse → Extension repos → Light novels tab).
 *
 * Source of truth is [NovelPreferences.addedRepoUrls] (StringSet pref) rather than a SQLDelight
 * table because LN repos carry no metadata equivalent to the manga side's signing key / website /
 * name — just a URL. The compose Browse → Extensions → Light novels sub-tab (CR6) reads the same
 * pref to aggregate plugins across every added registry.
 *
 * Koin DI throughout (Phase 7 convention; [NovelPreferences] and [LnPluginInstaller] are both
 * Koin-only).
 */
class LnRepoScreenModel :
    StateScreenModel<LnRepoScreenModel.State>(State.Loading), KoinComponent {

    private val novelPrefs: NovelPreferences by inject()
    private val installer: LnPluginInstaller by inject()

    private val internalEvent: MutableStateFlow<LnRepoEvent> = MutableStateFlow(LnRepoEvent.NoOp)
    val event: StateFlow<LnRepoEvent> = internalEvent.asStateFlow()

    init {
        screenModelScope.launchIO {
            novelPrefs.addedRepoUrls().changes().collectLatest { urls ->
                mutableState.update { State.Success(repos = urls.sorted().toImmutableList()) }
            }
        }
    }

    /**
     * Validate + add a new repo URL. Order of checks mirrors lnreader upstream:
     *  1. Regex pattern (https or http, ends with `/plugins.min.json`).
     *  2. Duplicate against the current set.
     *  3. Preflight HTTP fetch + JSON parse via [LnPluginInstaller.fetchRepo]. Catches both
     *     unreachable hosts and malformed registries before persisting.
     *
     * Each failure path emits a one-shot [LnRepoEvent] the UI consumes for a toast. Success
     * commits the URL to the pref set, which re-emits through [State.Success] via the init flow.
     */
    fun addRepo(url: String) {
        val trimmed = url.trim()
        if (!URL_REGEX.matches(trimmed)) {
            internalEvent.value = LnRepoEvent.InvalidUrl
            return
        }
        val current = novelPrefs.addedRepoUrls().get()
        if (trimmed in current) {
            internalEvent.value = LnRepoEvent.RepoAlreadyExists
            return
        }
        screenModelScope.launchIO {
            // Preflight. If the URL doesn't resolve to a parseable LnRegistry document, we want
            // to fail BEFORE persisting; otherwise the Light novels aggregate tab would log a
            // failed-fetch every time the user opens it.
            val ok = runCatching { installer.fetchRepo(trimmed) }.isSuccess
            if (!ok) {
                internalEvent.value = LnRepoEvent.PreflightFailed
                return@launchIO
            }
            novelPrefs.addedRepoUrls().set(current + trimmed)
            internalEvent.value = LnRepoEvent.Success
        }
    }

    fun deleteRepo(url: String) {
        screenModelScope.launchIO {
            novelPrefs.addedRepoUrls().set(novelPrefs.addedRepoUrls().get() - url)
        }
    }

    fun consumeEvent() {
        internalEvent.value = LnRepoEvent.NoOp
    }

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Success(
            val repos: ImmutableList<String>,
        ) : State {
            val isEmpty: Boolean
                get() = repos.isEmpty()
        }
    }

    companion object {
        /**
         * lnreader's pattern, slightly tightened to require a path component. Accepts http or
         * https because some self-hosted registries serve plain HTTP. Anchored at start + end.
         */
        private val URL_REGEX = Regex("^https?://.*/plugins\\.min\\.json$")
    }
}

sealed class LnRepoEvent {
    sealed class LocalizedMessage(val stringRes: StringResource) : LnRepoEvent()
    data object InvalidUrl : LocalizedMessage(MR.strings.invalid_repo_url)
    data object RepoAlreadyExists : LocalizedMessage(MR.strings.repo_already_exists)
    data object PreflightFailed : LocalizedMessage(MR.strings.invalid_repo_url)
    data object NoOp : LnRepoEvent()
    data object Success : LnRepoEvent()
}
