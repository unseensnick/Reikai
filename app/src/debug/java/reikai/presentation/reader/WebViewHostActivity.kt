package reikai.presentation.reader

import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity

/**
 * Test-only host giving a WebView a real window, so the compositor runs. It lives in the debug
 * variant rather than the test APK because instrumentation refuses to start an Activity resolving to
 * a different process. Nothing here is reachable from the app; the reader tests in androidTest are
 * its only callers. A ComponentActivity because the native viewport draws its boundaries in Compose,
 * which needs a lifecycle owner on the view tree.
 */
class WebViewHostActivity : ComponentActivity() {

    lateinit var webView: WebView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        root.addView(webView)
        setContentView(root)
    }
}
