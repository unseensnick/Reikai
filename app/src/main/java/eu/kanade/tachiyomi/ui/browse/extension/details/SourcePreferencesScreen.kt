package eu.kanade.tachiyomi.ui.browse.extension.details

import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.preference.DialogPreference
import androidx.preference.EditTextPreference
import androidx.preference.Preference // RK
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.preference.R
import androidx.preference.forEach
import androidx.preference.getOnBindEditTextListener
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.preference.SharedPreferencesDataStore
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.sourcePreferences
import eu.kanade.tachiyomi.widget.TachiyomiTextInputEditText.Companion.setIncognito
import exh.source.configurableSource
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import reikai.domain.track.site.OwnedSites // RK
import reikai.novel.source.NovelSettings
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.screens.LoadingScreen

class SourcePreferencesScreen(
    val sourceId: Long,
    // RK --> a novel source's id is text, which the manga source manager cannot name
    private val novelSourceId: String? = null,
    // RK <--
) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        // RK --> only the title is read here, so a novel source contributes just its name
        val source by produceState<Any?>(initialValue = null) {
            value = novelSourceId?.let { context.appGraph.novelSourceManager.nameOf(it) }
                ?: context.appGraph.sourceManager.getOrStub(sourceId)
        }
        // RK <--

        if (source == null) {
            LoadingScreen()
            return
        }

        // RK: do NOT push another screen from here. This screen hosts a Fragment through AndroidView
        // behind a one-time commit guarded by rememberSaveable, and on returning from a pushed screen
        // the composition comes back with that flag already set, so it takes the reflection re-attach
        // path and the fragment's view never returns, leaving an empty body. Upstream never pushes from
        // here, so the path is untested; a cross-link to the app-owned source settings was tried and
        // reverted for exactly this.
        Scaffold(
            topBar = {
                AppBar(
                    title = source.toString(),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            FragmentContainer(
                fragmentManager = (context as FragmentActivity).supportFragmentManager,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                add(it, SourcePreferencesFragment.getInstance(sourceId, novelSourceId), null) // RK
            }
        }
    }

    /**
     * From https://stackoverflow.com/questions/60520145/fragment-container-in-jetpack-compose/70817794#70817794
     */
    @Composable
    private fun FragmentContainer(
        fragmentManager: FragmentManager,
        modifier: Modifier = Modifier,
        commit: FragmentTransaction.(containerId: Int) -> Unit,
    ) {
        val containerId by rememberSaveable {
            mutableIntStateOf(View.generateViewId())
        }
        var initialized by rememberSaveable { mutableStateOf(false) }
        AndroidView(
            modifier = modifier,
            factory = { context ->
                FragmentContainerView(context)
                    .apply { id = containerId }
            },
            update = { view ->
                if (!initialized) {
                    fragmentManager.commit { commit(view.id) }
                    initialized = true
                } else {
                    fragmentManager.onContainerAvailable(view)
                }
            },
        )
    }

    /** Access to package-private method in FragmentManager through reflection */
    private fun FragmentManager.onContainerAvailable(view: FragmentContainerView) {
        val method = FragmentManager::class.java.getDeclaredMethod(
            "onContainerAvailable",
            FragmentContainerView::class.java,
        )
        method.isAccessible = true
        method.invoke(this, view)
    }

    // RK -->
    companion object {
        fun forNovel(novelSourceId: String) = SourcePreferencesScreen(0L, novelSourceId)
    }
    // RK <--
}

class SourcePreferencesFragment : PreferenceFragmentCompat() {

    override fun getContext(): Context? {
        val superCtx = super.getContext() ?: return null
        val tv = TypedValue()
        superCtx.theme.resolveAttribute(R.attr.preferenceTheme, tv, true)
        return ContextThemeWrapper(superCtx, tv.resourceId)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        lifecycleScope.launch {
            preferenceScreen = populateScreen()
        }
    }

    private suspend fun populateScreen(): PreferenceScreen {
        val sourceId = requireArguments().getLong(SOURCE_ID)
        // RK --> a novel source packaged as a tachiyomi-format apk hands over its own ConfigurableSource
        val novelEntrySource = requireArguments().getString(NOVEL_SOURCE_ID)?.let {
            requireContext().appGraph.novelSourceManager.get(it)
        }
        val novelSource = (novelEntrySource?.settings as? NovelSettings.PreferenceScreen)?.source
        // RK <--
        // RK --> a delegated source arrives wrapped, and the wrapper is not a ConfigurableSource, so
        // without unwrapping it the check below fails and the screen renders empty. Ported from
        // Komikku; the rule now lives in configurableSource, which the entry points gate on too.
        val source = novelSource ?: requireContext().appGraph.sourceManager.getOrStub(sourceId).configurableSource()
        // RK <--
        val sourceScreen = preferenceManager.createPreferenceScreen(requireContext())

        if (source is ConfigurableSource) {
            val dataStore = SharedPreferencesDataStore(source.sourcePreferences())
            preferenceManager.preferenceDataStore = dataStore

            source.setupPreferenceScreen(sourceScreen)
            // RK --> the settings a Reikai tracker took over along with the site; its parent, since an
            // extension may nest them in a category
            novelEntrySource?.let { OwnedSites.ownerOf(it) }?.hiddenSourceKeys?.forEach { key ->
                sourceScreen.findPreference<Preference>(key)?.let { it.parent?.removePreference(it) }
            }
            // RK <--
            sourceScreen.forEach { pref ->
                pref.isIconSpaceReserved = false
                pref.isSingleLineTitle = false
                if (pref is DialogPreference && pref.dialogTitle.isNullOrEmpty()) {
                    pref.dialogTitle = pref.title
                }

                // Apply incognito IME for EditTextPreference
                if (pref is EditTextPreference) {
                    val setListener = pref.getOnBindEditTextListener()
                    pref.setOnBindEditTextListener {
                        setListener?.onBindEditText(it)
                        it.setIncognito(requireContext().appGraph.basePreferences, lifecycleScope)
                    }
                }
            }
        }

        return sourceScreen
    }

    companion object {
        private const val SOURCE_ID = "source_id"
        private const val NOVEL_SOURCE_ID = "novel_source_id" // RK

        fun getInstance(sourceId: Long, novelSourceId: String? = null): SourcePreferencesFragment { // RK
            return SourcePreferencesFragment().apply {
                arguments = Bundle().apply {
                    putLong(SOURCE_ID, sourceId)
                    novelSourceId?.let { putString(NOVEL_SOURCE_ID, it) } // RK
                }
            }
        }
    }
}
