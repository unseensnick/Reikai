package eu.kanade.tachiyomi.source

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

/**
 * The JVM shape novel APKs link against, from tsundoku-otaku/extensions-lib 1.6.0-3's sources: its ABI
 * dump predates RateLimited and fetchPageText, so it cannot vouch for them. A rename here still compiles and only fails once an installed extension calls the member,
 * so this is the one check that catches it.
 */
class SourceApiContractTest {

    private fun Method.signature(): String {
        val params = parameterTypes.joinToString("") { it.descriptorString() }
        val kind = if (isDefault) "default" else "abstract"
        return "$kind $name($params)${returnType.descriptorString()}"
    }

    private fun Class<*>.signatures(): List<String> = declaredMethods.filterNot {
        it.isSynthetic
    }.map { it.signature() }

    @Test
    fun `SourceTracker matches the extension library`() {
        SourceTracker::class.java.signatures() shouldContainExactlyInAnyOrder listOf(
            "default getSupportsChapterTracking()Z",
            "default getSupportsFavoritesTracking()Z",
            "default onChaptersRead($S_MANGA$LIST$LIST$LIST$CONTINUATION)$OBJECT",
            "default onChaptersUnread($S_MANGA$LIST$LIST$LIST$CONTINUATION)$OBJECT",
            "default onFavorited($S_MANGA$LIST$CONTINUATION)$OBJECT",
            "default onUnfavorited($S_MANGA$LIST$CONTINUATION)$OBJECT",
        )
    }

    @Test
    fun `RateLimited matches the extension library`() {
        RateLimited::class.java.signatures() shouldContainExactlyInAnyOrder listOf(
            "abstract getMinimumDelayMillis()J",
            "default getRecommendedDelayMillis()J",
            "default getRecommendedPermits()I",
        )
    }

    @Test
    fun `Source answers the fetchPageText novel APKs declare`() {
        Source::class.java.signatures() shouldContain
            "default fetchPageText(Leu/kanade/tachiyomi/source/model/Page;$CONTINUATION)$OBJECT"
    }

    private companion object {
        const val S_MANGA = "Leu/kanade/tachiyomi/source/model/SManga;"
        const val LIST = "Ljava/util/List;"
        const val CONTINUATION = "Lkotlin/coroutines/Continuation;"
        const val OBJECT = "Ljava/lang/Object;"
    }
}
