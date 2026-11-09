package reikai.domain.download

/** The most sources the parallel source setting offers, for either content type. */
const val MAX_PARALLEL_SOURCES = 10

/**
 * The sources a downloader may run at once for a stored [limit]. A restored backup writes the setting
 * unchecked, and out of range it would stall the novel drain or throw in manga's `take`.
 */
fun parallelSourcesFor(limit: Int): Int = limit.coerceIn(1, MAX_PARALLEL_SOURCES)

/** The most pages the manga parallel page setting offers. */
const val MAX_PARALLEL_PAGES = 15

/** The pages a manga source job may fetch at once for a stored [limit]; below one its `Semaphore` throws. */
fun parallelPagesFor(limit: Int): Int = limit.coerceIn(1, MAX_PARALLEL_PAGES)
