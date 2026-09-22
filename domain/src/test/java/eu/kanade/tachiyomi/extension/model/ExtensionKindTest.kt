package eu.kanade.tachiyomi.extension.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ExtensionKindTest {

    @Test
    fun `a manga feature is a manga extension`() {
        Extension.Kind.fromFeatures(listOf("android.hardware.touchscreen", "tachiyomi.extension")) shouldBe
            Extension.Kind.MANGA
    }

    @Test
    fun `a novel feature is a tachiyomi-format novel extension`() {
        Extension.Kind.fromFeatures(listOf("tachiyomi.novelextension")) shouldBe Extension.Kind.TACHIYOMI_NOVEL
    }

    @Test
    fun `an apk declaring both loads as manga, as it did before novels were accepted`() {
        Extension.Kind.fromFeatures(listOf("tachiyomi.novelextension", "tachiyomi.extension")) shouldBe
            Extension.Kind.MANGA
    }

    @Test
    fun `an IReader feature is an IReader extension`() {
        Extension.Kind.fromFeatures(listOf("ireader", "ireader.extension")) shouldBe Extension.Kind.IREADER
    }

    @Test
    fun `an IReader extension's metadata keys are its source keys`() {
        Extension.Kind.IREADER.metadataPrefix shouldBe "source"
    }

    @Test
    fun `a tachiyomi-format kind's metadata keys follow its feature`() {
        Extension.Kind.TACHIYOMI_NOVEL.metadataPrefix shouldBe "tachiyomi.novelextension"
    }

    @Test
    fun `an apk declaring neither is not an extension`() {
        Extension.Kind.fromFeatures(listOf(null, "tachiyomi.extensions")) shouldBe null
    }
}
