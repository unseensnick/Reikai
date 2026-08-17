package eu.kanade.tachiyomi.di

import android.app.Application
import android.content.Context
import androidx.core.content.ContextCompat
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.cache.PagePreviewCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.AndroidSourceManager
import reikai.domain.novel.track.NovelDelayedTrackingStore
import reikai.novel.download.NovelDownloadCache
import reikai.novel.download.NovelDownloadManager
import reikai.novel.download.NovelDownloadProvider
import reikai.novel.host.LnPluginHost
import reikai.novel.host.LnPluginLoader
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSourceManager
import reikai.novel.update.LnPluginUpdateChecker
import tachiyomi.data.Database
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get

class AppModule(val app: Application) : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingleton(app)
        addSingleton<Context>(app)

        addSingletonFactory { ChapterCache(app, get()) }
        addSingletonFactory { CoverCache(app) }
        addSingletonFactory { PagePreviewCache(app) } // RK: adult-source page previews

        // RK --> light-novel plugin host: runs lnreader plugins on the shared OkHttp client
        addSingletonFactory { LnPluginHost(app, get<NetworkHelper>().client, get()) }
        addSingletonFactory { NovelSourceManager() }
        addSingletonFactory { LnPluginLoader(app, get<NetworkHelper>().client) }
        addSingletonFactory { LnPluginInstaller(get(), get(), get(), get(), get()) }
        addSingletonFactory { LnPluginUpdateChecker(get(), get()) }
        addSingletonFactory { NovelDownloadProvider() }
        addSingletonFactory { NovelDownloadCache() }
        addSingletonFactory { NovelDownloadManager(app) }
        // RK <--

        addSingletonFactory<SourceManager> { AndroidSourceManager(app, get(), get()) }
        addSingletonFactory { ExtensionManager(app) }

        addSingletonFactory { DownloadProvider(app) }
        addSingletonFactory { DownloadManager(app) }
        addSingletonFactory { DownloadCache(app) }

        addSingletonFactory { TrackerManager() }
        addSingletonFactory { DelayedTrackingStore(app) }
        // RK --> novel trackers
        addSingletonFactory { NovelDelayedTrackingStore(app) }
        // RK <--

        addSingletonFactory { ImageSaver(app) }

        // Asynchronously init expensive components for a faster cold start
        ContextCompat.getMainExecutor(app).execute {
            get<NetworkHelper>()

            get<SourceManager>()

            get<Database>()

            get<DownloadManager>()
        }
    }
}
