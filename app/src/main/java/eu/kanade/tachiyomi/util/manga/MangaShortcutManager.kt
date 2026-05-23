package eu.kanade.tachiyomi.util.manga

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import co.touchlab.kermit.Logger
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.size.SizeResolver
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.appwidget.TachiyomiWidgetManager
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.icon
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.main.SearchActivity
import eu.kanade.tachiyomi.ui.recents.RecentsPresenter
import eu.kanade.tachiyomi.ui.source.browse.BrowseSourceController
import eu.kanade.tachiyomi.util.system.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.manga.models.cover
import yokai.i18n.MR
import yokai.util.lang.getString
import kotlin.math.min

class MangaShortcutManager(
    val preferences: PreferencesHelper = Injekt.get(),
    val coverCache: CoverCache = Injekt.get(),
    val sourceManager: SourceManager = Injekt.get(),
) {

    fun updateShortcuts(context: Context) {
        // updateShortcuts runs on launchIO and chains image-loading + system-service work that
        // can outlive the Activity that triggered it. Capturing the Activity context in the
        // coroutine closure pins it as a leak under rapid theme recreates (LeakCanary chain:
        // CoroutineScheduler.Worker -> MangaShortcutManager$updateShortcuts$1.$context). All
        // calls below work with Application context (getSystemService, ImageRequest, Icon and
        // Intent construction), so we shadow the parameter with the unbound reference and let
        // the closure capture that instead.
        @Suppress("NAME_SHADOWING") val context = context.applicationContext
        launchIO {
            with(TachiyomiWidgetManager()) { context.init() }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N_MR1) {
                if (!preferences.showSeriesInShortcuts().get() && !preferences.showSourcesInShortcuts().get()) {
                    val shortcutManager = context.getSystemService(ShortcutManager::class.java)
                    shortcutManager.removeAllDynamicShortcuts()
                    return@launchIO
                }
                val shortcutManager = context.getSystemService(ShortcutManager::class.java)

                val recentManga = if (preferences.showSeriesInShortcuts().get()) {
                    RecentsPresenter.getRecentManga()
                } else {
                    emptyList()
                }
                val recentSources = if (preferences.showSourcesInShortcuts().get()) {
                    preferences.lastUsedSources().get().mapNotNull {
                        val splitS = it.split(":")
                        splitS.first().toLongOrNull()?.let { id ->
                            sourceManager.getOrStub(id) to splitS[1].toLong()
                        }
                    }
                } else {
                    emptyList()
                }
                val recents =
                    (recentManga.take(shortcutManager.maxShortcutCountPerActivity) + recentSources)
                        .sortedByDescending { it.second }
                        .map { it.first }
                        .take(shortcutManager.maxShortcutCountPerActivity)

                val shortcuts = recents.mapNotNull { item ->
                    when (item) {
                        is Manga -> {
                            val request = ImageRequest.Builder(context)
                                .data(item.cover())
                                .size(SizeResolver.ORIGINAL)
                                .build()
                            val bitmap = (
                                context.imageLoader
                                    .execute(request).image?.asDrawable(context.resources) as? BitmapDrawable
                                )?.bitmap

                            ShortcutInfo.Builder(
                                context,
                                "Manga-${item.id?.toString() ?: item.title}",
                            )
                                .setShortLabel(
                                    item.title.takeUnless { it.isBlank() }
                                        ?: context.getString(MR.strings.manga),
                                )
                                .setLongLabel(
                                    item.title.takeUnless { it.isBlank() }
                                        ?: context.getString(MR.strings.manga),
                                )
                                .setIcon(
                                    if (bitmap != null) if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                        Icon.createWithAdaptiveBitmap(bitmap.toSquare())
                                    } else {
                                        Icon.createWithBitmap(bitmap)
                                    }
                                    else {
                                        Icon.createWithResource(context, R.drawable.ic_book_24dp)
                                    },
                                )
                                .setIntent(
                                    SearchActivity.openMangaIntent(context, item.id, true)
                                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                                )
                                .build()
                        }
                        is Source -> {
                            val bitmap = (item.icon() as? BitmapDrawable)?.bitmap

                            ShortcutInfo.Builder(context, "Source-${item.id}")
                                .setShortLabel(item.name)
                                .setLongLabel(item.name)
                                .setIcon(
                                    if (bitmap != null) if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                        Icon.createWithAdaptiveBitmap(bitmap.toSquare())
                                    } else {
                                        Icon.createWithBitmap(bitmap)
                                    }
                                    else {
                                        Icon.createWithResource(
                                            context,
                                            R.drawable.sc_extensions_48dp,
                                        )
                                    },
                                )
                                .setIntent(
                                    Intent(
                                        context,
                                        SearchActivity::class.java,
                                    ).setAction(MainActivity.SHORTCUT_SOURCE)
                                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                        .putExtra(BrowseSourceController.SOURCE_ID_KEY, item.id),
                                )
                                .build()
                        }
                        else -> {
                            null
                        }
                    }
                }
                Logger.d { "Shortcuts: ${shortcuts.joinToString(", ") { it.longLabel ?: "n/a" }}" }
                shortcutManager.dynamicShortcuts = shortcuts
            }
        }
    }

    private fun Bitmap.toSquare(): Bitmap? {
        val side = min(width, height)

        val xOffset = (width - side) / 2
        // Slight offset for the y, since a lil bit under the top is usually the focus of covers
        val yOffset = ((height - side) / 2 * 0.25).toInt()

        return Bitmap.createBitmap(this, xOffset, yOffset, side, side)
    }
}
