package tachiyomi.core.common

object Constants {
    // RK -->
    // Every user-facing doc link in the app builds from this, so the host is written once. Help URLs
    // ship inside released APKs and outlive them, so a stale one cannot be recalled.
    const val URL_SITE = "https://reikai.app"
    const val URL_DOCS = "$URL_SITE/docs"

    const val URL_HELP = "$URL_DOCS/guides/troubleshooting/"
    const val URL_HELP_UPCOMING = "$URL_DOCS/faq/updates/upcoming"

    // RK <--
    const val URL_DONATE_PATREON = "https://patreon.com/mihon/membership"
    const val URL_DONATE_OPENCOLLECTIVE = "https://opencollective.com/mihon/contribute"
    const val URL_DISCORD = "https://discord.gg/mihon"

    const val MANGA_EXTRA = "manga"

    // RK: deep-link a per-novel update notification into its details (NovelScreen needs source + url)
    const val NOVEL_SOURCE_EXTRA = "novel_source"
    const val NOVEL_URL_EXTRA = "novel_url"

    // RK: which content type an Update errors deep link opens on (a ContentType name)
    const val CONTENT_TYPE_EXTRA = "content_type"

    const val MAIN_ACTIVITY = "eu.kanade.tachiyomi.ui.main.MainActivity"

    // Shortcut actions
    const val SHORTCUT_LIBRARY = "eu.kanade.tachiyomi.SHOW_LIBRARY"
    const val SHORTCUT_MANGA = "eu.kanade.tachiyomi.SHOW_MANGA"
    const val SHORTCUT_NOVEL = "eu.kanade.tachiyomi.SHOW_NOVEL" // RK

    const val SHORTCUT_UPDATES = "eu.kanade.tachiyomi.SHOW_RECENTLY_UPDATED"
    const val SHORTCUT_HISTORY = "eu.kanade.tachiyomi.SHOW_RECENTLY_READ"
    const val SHORTCUT_SOURCES = "eu.kanade.tachiyomi.SHOW_CATALOGUES"
    const val SHORTCUT_EXTENSIONS = "eu.kanade.tachiyomi.EXTENSIONS"
    const val SHORTCUT_DOWNLOADS = "eu.kanade.tachiyomi.SHOW_DOWNLOADS"
    const val SHORTCUT_UPDATE_ERRORS = "eu.kanade.tachiyomi.SHOW_UPDATE_ERRORS" // RK
}
