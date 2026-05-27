package yokai.core.migration.migrations

import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import yokai.core.migration.Migration
import yokai.core.migration.MigrationContext
import yokai.domain.novel.NovelPreferences
import yokai.domain.ui.UiPreferences

/**
 * One-time seed of the novel-side display preferences from the manga values.
 *
 * Before this lands, the Novels-tab Display sheet was writing to manga prefs and the novel
 * library was reading them too — so any non-default visual setting a user had configured
 * actually lives on the manga keys. After E lands, the Novels tab can optionally route through
 * its own [NovelPreferences.novelLibraryLayout] / etc. (when
 * `BasePreferences.useSharedLibraryDisplayPrefs` is off). Without this migration, a user who
 * flips the toggle would see the novel library reset to defaults — surprising and wrong.
 *
 * The migration copies each manga-side value into the matching novel key. After it runs:
 *   - Shared mode (the new default) still reads from manga prefs — nothing visible changes.
 *   - Flipping to independent mode starts the novel library from the user's existing look,
 *     and they can diverge it at will.
 *
 * Skips already-set novel keys so a re-run never overwrites a user-customised novel value.
 */
class SeedNovelDisplayPrefsMigration : Migration {
    override val version: Float = 168f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        val prefs = migrationContext.get<PreferencesHelper>() ?: return false
        val uiPrefs = migrationContext.get<UiPreferences>() ?: return false
        val novelPrefs = migrationContext.get<NovelPreferences>() ?: return false

        copyIfUnset(novelPrefs.novelLibraryLayout()) { prefs.libraryLayout().get() }
        copyIfUnset(novelPrefs.novelGridSize()) { prefs.gridSize().get() }
        copyIfUnset(novelPrefs.novelUseStaggeredGrid()) { prefs.useStaggeredGrid().get() }
        copyIfUnset(novelPrefs.novelUniformGrid()) { uiPrefs.uniformGrid().get() }
        copyIfUnset(novelPrefs.novelOutlineOnCovers()) { uiPrefs.outlineOnCovers().get() }
        copyIfUnset(novelPrefs.novelUnreadBadgeType()) { prefs.unreadBadgeType().get() }
        copyIfUnset(novelPrefs.novelDownloadBadge()) { prefs.downloadBadge().get() }
        copyIfUnset(novelPrefs.novelLanguageBadge()) { prefs.languageBadge().get() }
        copyIfUnset(novelPrefs.novelHideStartReadingButton()) { prefs.hideStartReadingButton().get() }
        copyIfUnset(novelPrefs.novelCategoryNumberOfItems()) { prefs.categoryNumberOfItems().get() }

        return true
    }

    private inline fun <T> copyIfUnset(
        pref: eu.kanade.tachiyomi.core.preference.Preference<T>,
        crossinline source: () -> T,
    ) {
        if (!pref.isSet()) pref.set(source())
    }
}
