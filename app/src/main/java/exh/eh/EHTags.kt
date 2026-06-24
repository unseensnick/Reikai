package exh.eh

/**
 * Phase 2b stub for E-Hentai's tag autocomplete catalogue. The real data set (~28k namespaced
 * tags) is large and only feeds the advanced tag-search UI, which lands in Phase 3; until then the
 * autocomplete filter simply offers no suggestions.
 */
object EHTags {
    fun getNamespaces(): List<String> = emptyList()

    fun getAllTags(): List<String> = emptyList()
}
