package reikai.domain.library

/**
 * The content-type filter shared by Browse and Library. Backs the
 * sticky `All / Manga / Novels` chip that replaces the Yōkai-era Browse Manga|LN sub-tab toggle.
 * Persisted via [tachiyomi.core.common.preference.getEnum], so the constant names are load-bearing.
 */
enum class ContentType {
    ALL,
    MANGA,
    NOVELS,
}
