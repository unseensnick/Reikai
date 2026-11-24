package reikai.presentation.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A PendingIntent is matched by its request code and its intent less the extras, and a notification's code is the
 * entry's id, which a manga and a novel can share, so a novel launch must differ from a manga one without them.
 */
@RunWith(RobolectricTestRunner::class)
class NovelReaderIntentTest {

    @Test
    fun `a novel launch is not the manga launch of the same ids to a PendingIntent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        ReaderActivity.newNovelIntent(context, novelId = 1L, chapterId = 2L)
            .filterEquals(ReaderActivity.newIntent(context, mangaId = 1L, chapterId = 2L)) shouldBe false
    }
}
