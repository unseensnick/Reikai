package eu.kanade.tachiyomi.di

import android.app.Application
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import exh.pref.DelegateSourcePreferences
import exh.source.ExhPreferences
import reikai.domain.category.CategoryIdPreferences
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.MangaPreferences
import reikai.domain.novel.NovelPreferences
import reikai.domain.recommendation.ReikaiRecommendationPreferences
import reikai.domain.source.ReikaiSourcePreferences
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get

class PreferenceModule(val app: Application) : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingletonFactory {
            SourcePreferences(get())
        }
        // RK -->
        addSingletonFactory {
            ReikaiLibraryPreferences(get())
        }
        addSingletonFactory {
            ReikaiRecommendationPreferences(get())
        }
        addSingletonFactory {
            NovelPreferences(get())
        }
        addSingletonFactory {
            MangaPreferences(get())
        }
        addSingletonFactory {
            ReikaiSourcePreferences(get())
        }
        addSingletonFactory {
            CategoryIdPreferences(
                libraryPreferences = get(),
                downloadPreferences = get(),
                novelPreferences = get(),
                reikaiLibraryPreferences = get(),
                reikaiSourcePreferences = get(),
            )
        }
        addSingletonFactory {
            DelegateSourcePreferences(get())
        }
        addSingletonFactory {
            ExhPreferences(get())
        }
        // RK <--
        addSingletonFactory {
            ReaderPreferences(get())
        }
        addSingletonFactory {
            TrackPreferences(get())
        }
        addSingletonFactory {
            UiPreferences(get())
        }
        addSingletonFactory {
            BasePreferences(app, get())
        }
    }
}
