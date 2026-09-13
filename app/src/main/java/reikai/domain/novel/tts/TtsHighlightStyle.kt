package reikai.domain.novel.tts

/**
 * How the paragraph being read aloud is marked. Persisted by name through `getEnum`, so the constant
 * names are load-bearing.
 */
enum class TtsHighlightStyle { BACKGROUND, UNDERLINE, OUTLINE }
