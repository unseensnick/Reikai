package reikai.presentation.reader.web

import java.util.UUID

/**
 * Which bridge calls the host hears. Only the document built last is heard, and nothing once the
 * viewport is torn down: the page being replaced goes on reporting until it unloads, and a chapter's
 * own script can reach the bridge too. The page's reports are dropped, not held, until it reports
 * ready, and it can send some before then. A tap or swipe is heard at once.
 */
internal class NovelDocumentGate {

    private var token: String? = null

    var isReady = false
        private set

    /** Starts a new document, closing the gate on everything the previous one sends. */
    fun open(): String = UUID.randomUUID().toString().also {
        token = it
        isReady = false
    }

    /** Returns whether [token] is the open document, which is then ready. */
    fun markReady(token: String): Boolean {
        if (token != this.token) return false
        isReady = true
        return true
    }

    fun admitsReport(token: String): Boolean = isReady && token == this.token

    fun admitsReaderCall(token: String): Boolean = token == this.token

    /** For a viewport being torn down: calls already posted to the main thread run after it. */
    fun close() {
        token = null
        isReady = false
    }
}
