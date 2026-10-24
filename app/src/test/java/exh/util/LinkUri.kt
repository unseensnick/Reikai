package exh.util

import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import java.net.URI

/** A link as a source's importer reads it; the JVM test classpath only stubs android.net.Uri. */
fun linkUri(url: String): Uri {
    val parsed = URI(url)
    return mockk {
        every { host } returns parsed.host
        every { path } returns parsed.path
        every { pathSegments } returns parsed.path.split('/').filter { it.isNotEmpty() }
    }
}
