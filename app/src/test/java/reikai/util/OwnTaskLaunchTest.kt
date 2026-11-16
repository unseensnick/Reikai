package reikai.util

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * Every way another app opens or shares something into Reikai, against the merged manifest. A share sheet
 * sends NEW_DOCUMENT and MULTIPLE_TASK, which put a standard or single-top activity in a Recents card of its
 * own, so each one that answers must refuse documents or be single-task (which ignores both flags).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class OwnTaskLaunchTest(
    @Suppress("UNUSED_PARAMETER") case: String,
    private val action: String,
    private val uri: String?,
    private val type: String?,
) {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `a share or open from another app cannot start a task of its own`() {
        val intent = Intent(action).setDataAndType(uri?.let(Uri::parse), type).setPackage(context.packageName)
        val answering = context.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo }
        answering.shouldNotBeEmpty()

        answering.filterNot {
            it.documentLaunchMode == ActivityInfo.DOCUMENT_LAUNCH_NEVER ||
                it.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK
        }.map { it.name }.shouldBeEmpty()
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun routes() = listOf(
            arrayOf("book shared", Intent.ACTION_SEND, null, "application/epub+zip"),
            arrayOf("comics shared", Intent.ACTION_SEND_MULTIPLE, null, "application/vnd.comicbook+zip"),
            arrayOf("book opened", Intent.ACTION_VIEW, "content://files/book.epub", "application/epub+zip"),
            arrayOf("backup opened", Intent.ACTION_VIEW, "content://files/a.tachibk", "application/octet-stream"),
            arrayOf("repo link", Intent.ACTION_VIEW, "mihon://extension-store?url=https://example.org/i.json", null),
            arrayOf("gallery link", Intent.ACTION_VIEW, "https://mangadex.org/title/abc", null),
            arrayOf("text shared", Intent.ACTION_SEND, null, "text/plain"),
        )
    }
}
