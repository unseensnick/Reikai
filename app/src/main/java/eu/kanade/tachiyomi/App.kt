package eu.kanade.tachiyomi

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Process
import android.webkit.WebView
import androidx.core.app.NotificationManagerCompat.NotificationWithIdAndTag
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.util.DebugLogger
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.createGraphFactory
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.setAppCompatDelegateThemeMode
import eu.kanade.tachiyomi.core.security.PrivacyPreferences
import eu.kanade.tachiyomi.crash.CrashActivity
import eu.kanade.tachiyomi.crash.GlobalExceptionHandler
import eu.kanade.tachiyomi.data.coil.BufferedSourceFetcher
import eu.kanade.tachiyomi.data.coil.ImageDecoder
import eu.kanade.tachiyomi.data.coil.MangaCoverFetcher
import eu.kanade.tachiyomi.data.coil.MangaCoverKeyer
import eu.kanade.tachiyomi.data.coil.MangaKeyer
import eu.kanade.tachiyomi.data.coil.PagePreviewFetcher
import eu.kanade.tachiyomi.data.coil.PagePreviewKeyer
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.ui.base.delegate.SecureActivityDelegate
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.ForegroundActivity
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.isDebugBuildType
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import exh.md.MangaDexTrackCoverFetcher
import exh.md.MangaDexTrackCoverKeyer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import logcat.AndroidLogcatLogger
import logcat.LogPriority
import logcat.LogcatLogger
import mihon.app.di.AppGraph
import mihon.app.di.injekt.MetroInjektRegistrar
import mihon.core.metro.GraphProvider
import mihon.core.migration.Migrator
import mihon.telemetry.TelemetryConfig
import org.conscrypt.Conscrypt
import reikai.data.coil.ExtensionIconFetcher
import reikai.data.coil.NovelCoverFetcher
import reikai.data.coil.NovelCoverKeyer
import reikai.data.coil.NovelImageFetcher
import reikai.data.coil.NovelImageKeyer
import reikai.data.work.WorkerStartFailures
import reikai.presentation.widget.UnifiedUpdatesWidgetManager
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.widget.WidgetManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import java.security.Security

class App :
    Application(),
    DefaultLifecycleObserver,
    SingletonImageLoader.Factory,
    GraphProvider<AppGraph>,
    Configuration.Provider {

    override val graph: AppGraph by lazy {
        createGraphFactory<AppGraph.Factory>().create(context = this, isDebugBuild = isDebugBuildType)
    }

    @Inject private lateinit var privacyPreferences: PrivacyPreferences

    @Inject private lateinit var networkPreferences: NetworkPreferences

    @Inject private lateinit var uiPreferences: UiPreferences

    @Inject private lateinit var widgetManager: WidgetManager

    @Inject private lateinit var unifiedUpdatesWidgetManager: UnifiedUpdatesWidgetManager // RK

    @Inject private lateinit var basePreferences: BasePreferences

    private val disableIncognitoReceiver = DisableIncognitoReceiver()

    /** False only in `:error_handler`, the process CrashActivity runs in. Unknown counts as main, so a
     *  process the platform will not name still starts normally. */
    private val isMainProcess: Boolean by lazy { currentProcessName()?.equals(packageName) ?: true }

    // RK --> WorkManager starts on demand from this, since its default start has no handler for a job
    // that throws while it is built (reikai.data.work.WorkerStartFailures). The manifest removes the default.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerInitializationExceptionHandler(
                WorkerStartFailures { tag, workerName ->
                    val notice = notificationBuilder(Notifications.CHANNEL_COMMON) {
                        setSmallIcon(R.drawable.ic_warning_white_24dp)
                        setContentTitle(stringResource(MR.strings.worker_start_failed))
                        setContentText(workerName)
                    }.build()
                    notify(listOf(NotificationWithIdAndTag(tag, Notifications.ID_WORKER_START_FAILURE, notice)))
                },
            )
            .build()
    // RK <--

    @SuppressLint("LaunchActivityFromNotification")
    override fun onCreate() {
        super<Application>.onCreate()

        // Must run before the graph is built, since injecting dependencies initializes WebView and the
        // suffix can't be set once a provider exists in the process. Secondary processes die otherwise.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val process = getProcessName()
            if (packageName != process) WebView.setDataDirectorySuffix(process)
        }

        TelemetryConfig.init(applicationContext)

        GlobalExceptionHandler.initialize(applicationContext, CrashActivity::class.java)

        // Assigned before the graph is built, which is safe because every binding is a lambda and
        // nothing dereferences the graph until one is called. An Injekt.get reached during graph
        // construction would re-enter the lazy below, so keep this pair adjacent.
        Injekt = InjektScope(MetroInjektRegistrar(application = this, graphProvider = this))

        // After the handler is installed, so a failure building the graph reaches CrashActivity
        // rather than dying on the platform handler.
        graph.inject(this)

        // TLS 1.3 support for Android < 10
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }

        // RK: the Cloudflare bypass needs a real window to solve an interactive challenge in, and
        //     the interceptor it runs from holds only this context.
        ForegroundActivity.register(this)

        if (!LogcatLogger.isInstalled) {
            val minLogPriority = when {
                networkPreferences.verboseLogging.get() -> LogPriority.VERBOSE
                BuildConfig.DEBUG -> LogPriority.DEBUG
                else -> LogPriority.INFO
            }
            LogcatLogger.install()
            LogcatLogger.loggers += AndroidLogcatLogger(minLogPriority)
        }

        setAppCompatDelegateThemeMode(uiPreferences.themeMode.get())

        // RK --> everything below belongs to the main process. CrashActivity runs in :error_handler,
        // so this method runs a second time there: the migrator would stamp a version for migrations
        // it never ran, and the warm-up and widget drivers would race their main-process twins.
        if (!isMainProcess) return
        // RK <--

        // Warm the expensive singletons off the critical path, as the old app module did. Posted, so
        // it runs after onCreate returns.
        ContextCompat.getMainExecutor(this).execute {
            graph.networkHelper
            graph.sourceManager
            graph.database
            graph.downloadManager
        }

        setupNotificationChannels()

        // RK: restore persisted cover colors for cover-based theming (Y11)
        graph.mangaCoverMetadata.load()

        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        val scope = ProcessLifecycleOwner.get().lifecycleScope

        // Show notification to disable Incognito Mode when it's enabled
        basePreferences.incognitoMode.changes()
            .onEach { enabled ->
                if (enabled) {
                    disableIncognitoReceiver.register()
                    notify(
                        Notifications.ID_INCOGNITO_MODE,
                        Notifications.CHANNEL_INCOGNITO_MODE,
                    ) {
                        setContentTitle(stringResource(MR.strings.pref_incognito_mode))
                        setContentText(stringResource(MR.strings.notification_incognito_text))
                        setSmallIcon(R.drawable.ic_glasses_24dp)
                        setOngoing(true)

                        val pendingIntent = PendingIntent.getBroadcast(
                            this@App,
                            0,
                            Intent(ACTION_DISABLE_INCOGNITO_MODE).setPackage(BuildConfig.APPLICATION_ID),
                            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
                        )
                        setContentIntent(pendingIntent)
                    }
                } else {
                    disableIncognitoReceiver.unregister()
                    cancelNotification(Notifications.ID_INCOGNITO_MODE)
                }
            }
            .launchIn(scope)

        privacyPreferences.analytics
            .changes()
            .onEach(TelemetryConfig::setAnalyticsEnabled)
            .launchIn(scope)

        privacyPreferences.crashlytics
            .changes()
            .onEach(TelemetryConfig::setCrashlyticsEnabled)
            .launchIn(scope)

        // Updates widget update
        widgetManager.init(scope)
        // RK: unified manga + novel updates widget (own driver: WidgetManager can't see novel flows)
        unifiedUpdatesWidgetManager.init(scope)

        initializeMigrator()
    }

    private fun currentProcessName(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        getProcessName()
    } else {
        val pid = Process.myPid()
        val activityManager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
        activityManager?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
    }

    private fun initializeMigrator() {
        val migrations = graph.migrations
        val preference = graph.preferenceStore.getInt(Preference.appStateKey("last_version_code"), 0)
        logcat {
            "Migration from ${preference.get()} to ${BuildConfig.VERSION_CODE} with ${migrations.size} migration(s)"
        }
        Migrator.initialize(
            old = preference.get(),
            new = BuildConfig.VERSION_CODE,
            migrations = migrations.toList(),
            onMigrationComplete = {
                logcat { "Updating last version to ${BuildConfig.VERSION_CODE}" }
                preference.set(BuildConfig.VERSION_CODE)
            },
        )
    }

    override fun newImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(this).apply {
            val callFactoryLazy = lazy { graph.networkHelper.client }
            // RK: read here rather than as the injected App fields upstream uses: newImageLoader runs
            // lazily, while a field would build SourceManager at graph.inject in every process,
            // :error_handler included, which the main-process gate above keeps it out of.
            val coverCache = graph.coverCache
            val sourceManager = graph.sourceManager
            val mangaCoverMetadata = graph.mangaCoverMetadata // RK
            components {
                // NetworkFetcher.Factory
                add(OkHttpNetworkFetcherFactory(callFactoryLazy::value))
                // Decoder.Factory
                add(ImageDecoder.Factory())
                // Fetcher.Factory
                add(BufferedSourceFetcher.Factory())
                // RK: the last argument is Reikai's cover-colour extraction (Y11)
                add(MangaCoverFetcher.MangaCoverFactory(callFactoryLazy, coverCache, sourceManager, mangaCoverMetadata))
                add(MangaCoverFetcher.MangaFactory(callFactoryLazy, coverCache, sourceManager, mangaCoverMetadata))
                // RK: light-novel covers and chapter pictures, each with its source's image headers
                val novelImageRequests = lazy { graph.novelImageRequests }
                add(NovelCoverFetcher.Factory(novelImageRequests, coverCache))
                add(NovelImageFetcher.Factory(novelImageRequests))
                // RK: a novel extension app's icon, named as an address
                add(ExtensionIconFetcher.Factory(lazy { graph.extensionManager }, lazy { graph.novelPreferences }))
                // RK: adult-source gallery page-preview thumbnails
                add(PagePreviewFetcher.Factory(callFactoryLazy, lazy { graph.pagePreviewCache }, sourceManager))
                // RK: MDList tracker-search covers, fetched via the MangaDex source client so the
                // cover CDN doesn't 400 the app's browser User-Agent
                add(
                    MangaDexTrackCoverFetcher.Factory(
                        callFactoryLazy,
                        graph.sourcePreferences,
                        graph.reikaiSourcePreferences,
                        sourceManager,
                    ),
                )
                // Keyer
                add(MangaCoverKeyer(coverCache))
                add(MangaKeyer())
                add(NovelCoverKeyer()) // RK
                add(NovelImageKeyer()) // RK
                add(PagePreviewKeyer()) // RK
                add(MangaDexTrackCoverKeyer()) // RK
            }

            memoryCache(
                MemoryCache.Builder()
                    .maxSizePercent(context)
                    .build(),
            )

            crossfade((300 * this@App.animatorDurationScale).toInt())
            allowRgb565(DeviceUtil.isLowRamDevice(this@App))
            if (networkPreferences.verboseLogging.get()) logger(DebugLogger())

            // Coil spawns a new thread for every image load by default
            fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(8))
            decoderCoroutineContext(Dispatchers.IO.limitedParallelism(3))
        }
            .build()
    }

    override fun onStart(owner: LifecycleOwner) {
        SecureActivityDelegate.onApplicationStart(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        SecureActivityDelegate.onApplicationStopped(this)
    }

    override fun getPackageName(): String {
        try {
            // Override the value passed as X-Requested-With in WebView requests
            val stackTrace = Thread.currentThread().stackTrace
            val isChromiumCall = stackTrace.any { trace ->
                trace.className.lowercase() in setOf("org.chromium.base.buildinfo", "org.chromium.base.apkinfo") &&
                    trace.methodName.lowercase() in setOf("getall", "getpackagename", "<init>")
            }

            if (isChromiumCall) return WebViewUtil.spoofedPackageName(applicationContext)
        } catch (_: Exception) {
        }

        return super.getPackageName()
    }

    private fun setupNotificationChannels() {
        try {
            Notifications.createChannels(this)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to modify notification channels" }
        }
    }

    private inner class DisableIncognitoReceiver : BroadcastReceiver() {
        private var registered = false

        override fun onReceive(context: Context, intent: Intent) {
            basePreferences.incognitoMode.set(false)
        }

        fun register() {
            if (!registered) {
                ContextCompat.registerReceiver(
                    this@App,
                    this,
                    IntentFilter(ACTION_DISABLE_INCOGNITO_MODE),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                registered = true
            }
        }

        fun unregister() {
            if (registered) {
                unregisterReceiver(this)
                registered = false
            }
        }
    }
}

private const val ACTION_DISABLE_INCOGNITO_MODE = "tachi.action.DISABLE_INCOGNITO_MODE"
