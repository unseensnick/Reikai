package reikai.domain.recommendation.taste

/**
 * Whether a tracked entry is sexually explicit, as its tracker reports it. Scope is sexual content
 * (erotica, sex, nudity), never violence or gore.
 *
 * [UNKNOWN] is a real state rather than a null: a tracker that cannot answer has to stay
 * distinguishable from one that answered "no", or every gap silently reads as clean.
 */
enum class AdultContent {
    ADULT,
    CLEAN,
    UNKNOWN,
}

/**
 * Resolves whether an entry counts as sexually explicit. The tracker's own answer wins; only where
 * it cannot answer does this fall back to matching the entry's tags, which are already lowercased
 * by `toTagKey`.
 */
fun TrackedEntry.isSexuallyExplicit(): Boolean = when (adult) {
    AdultContent.ADULT -> true
    AdultContent.CLEAN -> false
    AdultContent.UNKNOWN -> tags.any { tag -> SEXUAL_CONTENT_TAGS.any { it in tag } }
}

/**
 * Substrings marking a genre or tag as sexually explicit, taken from the services' own published
 * vocabularies. A term qualifies only where that service defines it as sexual, so a definition
 * phrased as a disjunction ("violence, gore, sexual content and/or strong language") is out: it
 * would catch violence-only series. Narrower than `reikai.util.isLewd` on purpose. The near-misses
 * this must not match are pinned by AdultContentTest.
 */
private val SEXUAL_CONTENT_TAGS = listOf(
    // Explicit by definition on MyAnimeList, AniList, MangaUpdates, Shikimori and Hikka.
    "hentai",
    // Covers Erotica and erotic-*; MangaBaka also rates a work `erotica`.
    "erotic",
    // MangaUpdates: "profane or offensive, particularly with regards to sexual content".
    "smut",
    // MangaBaka's `pornographic` rating, and pornography as a tag.
    "pornograph",
    // MangaUpdates, both defined as sexual attraction to minors. Full words: `loli` alone overmatches.
    "lolicon",
    "shotacon",
    // AniList tags carrying isAdult, all from its own "Sexual Content" category.
    "futanari",
    "ahegao",
    "netorare",
    "netorase",
    "netori",
    "oyakodon",
    // Bangumi's community tags are Chinese and Japanese, so every Latin term above misses them.
    // Measured over 50 adult-tagged Bangumi books, these carry recall from 39 to 43. Shikimori's
    // genres come back English, so the Cyrillic pair only ever fires on candidates from elsewhere.
    "хентай",
    "эротика",
    "里番",
    "工口",
    "情色",
    "18禁",
    "エロ",
    // The two standard Japanese classifications for adult comics.
    "成年コミック",
    "成人漫画",
    "r18",
    "18+",
)
