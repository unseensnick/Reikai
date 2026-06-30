package exh.source

// Delegated lewd source IDs. Trimmed from Komikku's SourceIds to the sources Reikai enhances
// in Phase 1; MangaDex / Comick ids come with their later phases.
const val PURURIN_SOURCE_ID = 2221515250486218861L
const val EIGHTMUSES_SOURCE_ID = 1802675169972965535L

// E-Hentai / ExHentai built-in source IDs (Phase 2). The "Multi" (all-languages) variants
// double as the built-in source IDs; the language-specific entries map the stock E-Hentai
// extension's per-language sources so installs carrying them resolve to the enhanced source.
const val EH_SOURCE_ID = 1713178126840476467L // LEWD_SOURCE_SERIES + 1
const val EXH_SOURCE_ID = 6225928719850211219L // LEWD_SOURCE_SERIES + 2

// Built-in nhentai.net source (no extension needed, talks to nhentai's v2 API). Its own fixed id,
// independent of the legacy delegated nHentai wrapper (which keeps enhancing installed extensions).
const val NHENTAI_NET_SOURCE_ID = 1713178126840476469L // LEWD_SOURCE_SERIES + 3

// The stock E-Hentai extension package. The built-in EH sources have no installed extension, so this
// is the synthetic key used to mark them incognito (see GetIncognitoState's // RK island).
const val EH_PACKAGE = "eu.kanade.tachiyomi.extension.all.ehentai"

val EHENTAI_EXT_SOURCES = mapOf(
    8100626124886895451L to "ja", // E-Hentai (Ja)
    57122881048805941L to "en", // E-Hentai (En)
    4678440076103929247L to "zh", // E-Hentai (Zh)
    1876021963378735852L to "nl", // E-Hentai (Nl)
    3955189842350477641L to "fr", // E-Hentai (Fr)
    4348288691341764259L to "de", // E-Hentai (De)
    773611868725221145L to "hu", // E-Hentai (Hu)
    5759417018342755550L to "it", // E-Hentai (It)
    825187715438990384L to "ko", // E-Hentai (Ko)
    6116711405602166104L to "pl", // E-Hentai (Pl)
    7151438547982231541L to "pt-BR", // E-Hentai (PtBr)
    2171445159732592630L to "ru", // E-Hentai (Ru)
    3032959619549451093L to "es", // E-Hentai (Es)
    5980349886941016589L to "th", // E-Hentai (Th)
    6073266008352078708L to "vi", // E-Hentai (Vi)
    5499077866612745456L to "none", // E-Hentai (None)
    6140480779421365791L to "other", // E-Hentai (Other)
    EH_SOURCE_ID to "all", // E-Hentai (Multi)
)

val EXHENTAI_EXT_SOURCES = mapOf(
    4069364610143267166L to "ja", // E-Hentai (Ja)
    6024400692103629868L to "en", // E-Hentai (En)
    1394807835077780591L to "zh", // E-Hentai (Zh)
    3403092744483051659L to "nl", // E-Hentai (Nl)
    2063958147920418330L to "fr", // E-Hentai (Fr)
    4277540540471304114L to "de", // E-Hentai (De)
    5580677059017993793L to "hu", // E-Hentai (Hu)
    6348816521182710144L to "it", // E-Hentai (It)
    933801322201782118L to "ko", // E-Hentai (Ko)
    250678340923599076L to "pl", // E-Hentai (Pl)
    7151438547982231541L to "pt-BR", // E-Hentai (PtBr)
    2298110591802103872L to "ru", // E-Hentai (Ru)
    1471697890032830855L to "es", // E-Hentai (Es)
    5297549186919793998L to "th", // E-Hentai (Th)
    1904260310237764859L to "vi", // E-Hentai (Vi)
    2351087900641384311L to "none", // E-Hentai (None)
    609703168489499116L to "other", // E-Hentai (Other)
    EXH_SOURCE_ID to "all", // E-Hentai (Multi)
)

val eHentaiSourceIds = EHENTAI_EXT_SOURCES.keys + EXHENTAI_EXT_SOURCES.keys

// Source ids skipped by the normal library update sweep. E-Hentai / ExHentai / Pururin galleries
// default to ALWAYS_UPDATE but never gain chapters the usual way: E-Hentai has its own version
// checker (EHentaiUpdateWorker), and re-fetching a whole gallery on every library update wastes
// requests and risks E-Hentai rate-limits / bans. We register a built-in E-Hentai source per
// language, so a saved entry can carry any id in eHentaiSourceIds, not just the canonical EH/EXH
// ones. The built-in nhentai.net source is excluded by its fixed id; nHentai entries from an
// installed extension are excluded separately at runtime (AndroidSourceManager.nHentaiDelegatedSourceIds),
// since that id varies by extension version.
val LIBRARY_UPDATE_EXCLUDED_SOURCES = eHentaiSourceIds + PURURIN_SOURCE_ID + NHENTAI_NET_SOURCE_ID
