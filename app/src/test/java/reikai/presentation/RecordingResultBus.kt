package reikai.presentation

import androidx.navigation3.runtime.result.ResultEventBus
import io.mockk.every
import io.mockk.mockk

/**
 * A result bus that records each send as (key, result), for a test of the sending side outside a
 * composition, where nothing can collect from the real one.
 */
class RecordingResultBus {
    val sent = mutableListOf<Pair<String, Any?>>()

    val bus: ResultEventBus = mockk {
        every { sendResult(any<String>(), any<Any>()) } answers { sent += firstArg<String>() to secondArg<Any?>() }
    }
}

/** The key a result of type [T] goes out on when sent without one, as ResultEffect<T> reads it. */
inline fun <reified T> resultKey(): String = T::class.toString()
