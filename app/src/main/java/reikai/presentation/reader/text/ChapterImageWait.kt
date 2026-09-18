package reikai.presentation.reader.text

/**
 * How long a saved place waits for its chapter's pictures before landing without them, in both renderers:
 * the page reads it as `IMAGE_WAIT_MS` (reader.js). A picture whose request never answers must not strand
 * the reader at the chapter's top.
 */
internal const val CHAPTER_IMAGE_WAIT_MS = 3_000L
