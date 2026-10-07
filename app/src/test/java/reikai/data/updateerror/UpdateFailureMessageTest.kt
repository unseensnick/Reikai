package reikai.data.updateerror

import android.content.Context
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

class UpdateFailureMessageTest {

    private val context by lazy {
        mockk<Context> { every { stringResource(MR.strings.unknown) } returns "Unknown" }
    }

    @BeforeEach
    fun setUp() {
        mockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
    }

    @Test
    fun `a failure with no message reads Unknown`() {
        with(context) { RuntimeException().updateFailureMessage() } shouldBe "Unknown"
    }
}
