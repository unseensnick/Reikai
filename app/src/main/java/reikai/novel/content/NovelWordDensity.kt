package reikai.novel.content

/**
 * Word statistics over a novel's downloaded chapters. [totalChapters] is every chapter in scope, on disk
 * or not; [unreadableChapters] were on disk but could not be read, so they are kept apart from the rest.
 */
data class NovelWordDensity(
    val totalWords: Long,
    val countedChapters: Int,
    val totalChapters: Int,
    val unreadableChapters: Int = 0,
) {
    val notDownloadedChapters: Int
        get() = (totalChapters - countedChapters - unreadableChapters).coerceAtLeast(0)

    val averageWords: Long
        get() = if (countedChapters == 0) 0 else totalWords / countedChapters

    val tier: Int
        get() = densityTier(averageWords)

    companion object {
        const val FIRST_TIER_MAX_WORDS = 400L
        const val TIER_STEP_WORDS = 100L
        const val MAX_TIER = 10

        /** 1 up to 400 words a chapter, then one more every 100 words, capped at [MAX_TIER]. */
        fun densityTier(averageWords: Long): Int {
            if (averageWords <= FIRST_TIER_MAX_WORDS) return 1
            val tier = 2 + (averageWords - FIRST_TIER_MAX_WORDS - 1) / TIER_STEP_WORDS
            return tier.coerceAtMost(MAX_TIER.toLong()).toInt()
        }

        fun tierMinWords(tier: Int): Long =
            if (tier <= 1) 1 else FIRST_TIER_MAX_WORDS + 1 + (tier - 2) * TIER_STEP_WORDS

        /** Null for the open-ended top tier. */
        fun tierMaxWords(tier: Int): Long? =
            if (tier >= MAX_TIER) null else FIRST_TIER_MAX_WORDS + (tier - 1) * TIER_STEP_WORDS
    }
}
