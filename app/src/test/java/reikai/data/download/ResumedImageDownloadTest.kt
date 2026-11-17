package reikai.data.download

import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.ProgressListener
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.random.Random

/**
 * A real server behind the app's own resumable call, as a page download meets it. The XOR client is
 * shaped like an extension that decrypts its images in an application interceptor: it never sees the
 * Range the call adds below it, so it decodes a resumed answer from its key's start.
 */
class ResumedImageDownloadTest {

    private val image = Random(7).nextBytes(20_000)
    private val key = "UySkp0BzPhwlvP2V".toByteArray()
    private val ranges: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    private var honoursRange = true
    private var encrypted = true

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/page.jpg") { exchange ->
            val served = if (encrypted) image.xored() else image
            val range = exchange.requestHeaders.getFirst("Range")
            ranges += range
            val start = range?.takeIf { honoursRange }?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
            val body = if (start == null) served else served.copyOfRange(start, served.size)
            if (start != null) {
                exchange.responseHeaders.add("Content-Range", "bytes $start-${served.size - 1}/${served.size}")
            }
            exchange.sendResponseHeaders(if (start == null) 200 else 206, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }

    private val url = "http://127.0.0.1:${server.address.port}/page.jpg"

    private val decrypting = OkHttpClient.Builder().addInterceptor(Interceptor(::decrypt)).build()

    private fun decrypt(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body
        return response.newBuilder().body(body.bytes().xored().toResponseBody(body.contentType())).build()
    }

    /** A source that serves the image as it is. */
    private val plain = OkHttpClient()

    private val noProgress = object : ProgressListener {
        override fun update(bytesRead: Long, contentLength: Long, done: Boolean) = Unit
    }

    @AfterEach
    fun tearDown() = server.stop(0)

    private fun ByteArray.xored() = ByteArray(size) { (this[it].toInt() xor key[it % key.size].toInt()).toByte() }

    /** Resumes from [cutAt] bytes already on disk, as the downloader does after a dropped connection. */
    private suspend fun resume(client: OkHttpClient, cutAt: Int): ByteArray {
        val disk = ByteArrayOutputStream().apply { write(image, 0, cutAt) }
        ResumedImageDownload.write(
            existingSize = cutAt.toLong(),
            stored = { from -> disk.toByteArray().copyOfRange(from.toInt(), disk.size()) },
            fetch = { from -> client.newCachelessCallWithProgress(GET(url), noProgress, from).awaitSuccess() },
            open = { append -> disk.apply { if (!append) reset() } },
        ).close()
        return disk.toByteArray()
    }

    @Test
    fun `an image a source decrypts comes out whole after a resume`() = runTest {
        resume(decrypting, cutAt = 9_999).contentEquals(image) shouldBe true
    }

    @Test
    fun `an image a source decrypts is fetched again whole rather than appended`() = runTest {
        resume(decrypting, cutAt = 9_999)

        ranges shouldBe listOf("bytes=${9_999 - ResumedImageDownload.OVERLAP}-", null)
    }

    @Test
    fun `a resume whose key happens to line up is appended`() = runTest {
        resume(decrypting, cutAt = 16 * 600 + ResumedImageDownload.OVERLAP)

        ranges shouldBe listOf("bytes=${16 * 600}-")
    }

    @Test
    fun `a lined-up resume comes out whole`() = runTest {
        resume(decrypting, cutAt = 16 * 600 + ResumedImageDownload.OVERLAP).contentEquals(image) shouldBe true
    }

    @Test
    fun `an image served as it is resumes by appending`() = runTest {
        encrypted = false

        resume(plain, cutAt = 9_999)

        ranges shouldBe listOf("bytes=${9_999 - ResumedImageDownload.OVERLAP}-")
    }

    @Test
    fun `an image served as it is comes out whole after a resume`() = runTest {
        encrypted = false

        resume(plain, cutAt = 9_999).contentEquals(image) shouldBe true
    }

    @Test
    fun `a little on disk is fetched whole with no range`() = runTest {
        resume(decrypting, cutAt = 500)

        ranges shouldBe listOf(null)
    }

    @Test
    fun `a server that ignores the range overwrites with the whole image`() = runTest {
        honoursRange = false

        resume(decrypting, cutAt = 9_999).contentEquals(image) shouldBe true
    }
}
