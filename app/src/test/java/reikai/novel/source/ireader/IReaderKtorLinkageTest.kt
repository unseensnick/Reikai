package reikai.novel.source.ireader

import io.kotest.matchers.shouldBe
import io.ktor.http.HttpMethod
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * IReader extensions link against the host's Ktor, and each was compiled against whichever Ktor its
 * source-api pinned. One built on Ktor 3.4 or later reads `HttpMethod.Get` as a static field; an older
 * one calls `HttpMethod.Companion.getGet()`. Both must link, or one set fails with an IllegalAccessError
 * or a NoSuchMethodError at its first request.
 */
class IReaderKtorLinkageTest {

    @Test
    fun `an extension built on newer Ktor can read HttpMethod's constants as public static fields`() {
        val modifiers = HttpMethod::class.java.getField("Get").modifiers

        (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers)) shouldBe true
    }

    @Test
    fun `an extension built on older Ktor can still call the companion getter`() {
        HttpMethod.Companion::class.java.getMethod("getGet").invoke(HttpMethod.Companion) shouldBe HttpMethod.Get
    }
}
