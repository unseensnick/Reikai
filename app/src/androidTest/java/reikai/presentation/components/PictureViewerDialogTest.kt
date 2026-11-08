package reikai.presentation.components

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Base64
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import mihon.app.di.appGraph
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import reikai.presentation.reader.WebViewHostActivity
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * The full-screen picture viewer a cover and a chapter's picture open in, with both of its renderers: the
 * picture fitted, closed by a tap on it, and turned a quarter by Rotate so a wide one fills an upright screen.
 * With the `pictureCapture` argument naming the device it also saves screenshots for looking at by eye.
 */
@RunWith(Parameterized::class)
class PictureViewerDialogTest(private val highQuality: Boolean) {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<WebViewHostActivity>
    private val dismissed = AtomicInteger()
    private var rendererWas = false

    @Before
    fun setUp() {
        val preference = instrumentation.targetContext.appGraph.basePreferences.highQualityRenderer
        rendererWas = preference.get()
        preference.set(highQuality)
        scenario = ActivityScenario.launch(WebViewHostActivity::class.java)
        scenario.onActivity { activity ->
            activity.setContent {
                PictureViewerDialog(
                    picture = wideRedThenBlue(),
                    snackbarHostState = SnackbarHostState(),
                    onShareClick = {},
                    onSaveClick = {},
                    onDismissRequest = { dismissed.incrementAndGet() },
                    canRotate = true,
                )
            }
        }
        awaitWhile { colourAcross(0.5f) == null }
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
        instrumentation.targetContext.appGraph.basePreferences.highQualityRenderer.set(rendererWas)
    }

    @Test
    fun aWidePictureOpensFittedAcrossTheScreen() {
        capture("fitted")

        assertEquals("[$name] left then right of the middle row", Color.RED to Color.BLUE, colourAcross(0.5f))
    }

    /** Fitted, the picture reaches both sides of the screen: a smaller one leaves a margin. */
    @Test
    fun aWidePictureOpensAcrossTheWholeWidth() {
        val (width, _) = screen()

        assertTrue("[$name] spans ${pictureWidth()} of $width", pictureWidth() >= width * 0.97f)
    }

    /** Fitted across, the picture is a third as tall as the screen is wide: a zoomed or cropped one is taller. */
    @Test
    fun aWidePictureOpensAThirdAsTallAsTheScreenIsWide() {
        val (width, _) = screen()
        val height = pictureHeight()

        assertTrue("[$name] is $height tall on a $width wide screen", abs(height - width / 3f) <= width * 0.03f)
    }

    /**
     * Two quick turns leave the picture as it was. The turned load is the slower one, so a superseded load
     * finishing last would leave it turned; this shows that only on a device where the two loads overlap.
     */
    @Test
    fun twoQuickRotatesLeaveThePictureUpright() {
        val rotate = rotateButton()
        rotate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        // A frame apart, so each turn reaches its own load, and well inside the time a load takes.
        Thread.sleep(40)
        rotate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Thread.sleep(2_000)

        assertEquals("[$name] left then right of the middle row", Color.RED to Color.BLUE, colourAcross(0.5f))
    }

    @Test
    fun aTapOnThePictureClosesIt() {
        val (width, height) = screen()

        tapAt(width / 2f, height / 2f)

        awaitWhile { dismissed.get() == 0 }
        assertEquals("[$name] closes", 1, dismissed.get())
    }

    /** Turned a quarter clockwise, the picture's left end is at the top, and it is drawn taller than it was. */
    @Test
    fun rotateTurnsThePictureAQuarter() {
        clickRotate()
        awaitWhile { colourDown(0.5f) != (Color.RED to Color.BLUE) }
        capture("rotated")

        assertEquals("[$name] above then below the middle", Color.RED to Color.BLUE, colourDown(0.5f))
    }

    /** Not a check of the zoom itself, which is the viewer's own: a screenshot zoomed in, and still open. */
    @Test
    fun aDoubleTapZoomsIn() {
        val (width, height) = screen()
        tapAt(width / 2f, height / 2f)
        // Past GestureDetector's 40 ms floor between the first lift and the second press.
        Thread.sleep(120)
        tapAt(width / 2f, height / 2f)
        Thread.sleep(1_200)
        capture("zoomed")

        assertEquals("[$name] a double tap is not a close", 0, dismissed.get())
    }

    private val name get() = if (highQuality) "high quality" else "standard"

    private fun capture(case: String) {
        val device = InstrumentationRegistry.getArguments().getString("pictureCapture") ?: return
        val renderer = if (highQuality) "webgpu" else "ssiv"
        val shot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), "${device}_${renderer}_viewer_${case}_after.png")
            .outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shot.recycle()
    }

    private fun clickRotate() {
        assertTrue("[$name] Rotate did not click", rotateButton().performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun rotateButton(): AccessibilityNodeInfo {
        var button: AccessibilityNodeInfo? = null
        var seen = emptyList<String>()
        awaitWhile {
            val automation = instrumentation.uiAutomation
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            val nodes = automation.windows.mapNotNull { it.root }.flatMap { descendants(it) }
            seen = nodes.mapNotNull { it.contentDescription?.toString() ?: it.text?.toString() }
            button = nodes.firstOrNull { it.contentDescription?.toString() == ROTATE }
                ?.let { node -> generateSequence(node) { it.parent }.firstOrNull { it.isClickable } }
            button == null
        }
        return checkNotNull(button) { "[$name] Rotate was not found among $seen" }
    }

    private fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        listOf(node) + (0 until node.childCount).mapNotNull { node.getChild(it) }.flatMap { descendants(it) }

    /** The colours either side of the screen's middle, a quarter of its width in, at [row] of its height. */
    private fun colourAcross(row: Float): Pair<Int, Int>? = sample { width, height ->
        listOf(width / 4 to (height * row).toInt(), width * 3 / 4 to (height * row).toInt())
    }

    /** The colours above and below the screen's middle, a sixth of its height away, down its middle at [column]. */
    private fun colourDown(column: Float): Pair<Int, Int>? = sample { width, height ->
        listOf((width * column).toInt() to height / 2 - height / 6, (width * column).toInt() to height / 2 + height / 6)
    }

    /** How many pixels the picture's red half covers down the screen's column a quarter of the way in. */
    private fun pictureHeight(): Int = screenshot { shot ->
        (0 until shot.height).count { named(shot.getPixel(shot.width / 4, it)) == Color.RED }
    }

    /** From the first to the last of the picture's pixels across the middle of its red half's run down the screen. */
    private fun pictureWidth(): Int = screenshot { shot ->
        val column = (0 until shot.height).filter { named(shot.getPixel(shot.width / 4, it)) == Color.RED }
        val row = column[column.size / 2]
        val drawn = (0 until shot.width).filter { named(shot.getPixel(it, row)) != null }
        drawn.last() - drawn.first() + 1
    }

    private fun <T> screenshot(read: (Bitmap) -> T): T {
        val shot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        return try {
            read(shot)
        } finally {
            shot.recycle()
        }
    }

    private fun sample(points: (Int, Int) -> List<Pair<Int, Int>>): Pair<Int, Int>? {
        val shot = instrumentation.uiAutomation.takeScreenshot() ?: return null
        val (first, second) = points(shot.width, shot.height).map { (x, y) -> named(shot.getPixel(x, y)) }
        shot.recycle()
        return if (first == null || second == null) null else first to second
    }

    private fun named(pixel: Int): Int? = when {
        Color.red(pixel) > 200 && Color.green(pixel) < 60 && Color.blue(pixel) < 60 -> Color.RED
        Color.blue(pixel) > 200 && Color.red(pixel) < 60 && Color.green(pixel) < 60 -> Color.BLUE
        else -> null
    }

    private fun screen(): Pair<Int, Int> = instrumentation.targetContext.resources.displayMetrics.let {
        it.widthPixels to it.heightPixels
    }

    private fun tapAt(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + 50).forEach { (action, time) ->
            val event = MotionEvent.obtain(down, time, action, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            instrumentation.uiAutomation.injectInputEvent(event, true)
            event.recycle()
        }
    }

    private fun awaitWhile(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (condition() && System.currentTimeMillis() < deadline) Thread.sleep(100)
    }

    /** A picture three times as wide as it is tall, red on its left half and blue on its right. */
    private fun wideRedThenBlue(): String {
        val bitmap = Bitmap.createBitmap(1_500, 500, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLUE)
        canvas.drawRect(0f, 0f, 750f, 500f, Paint().apply { color = Color.RED })
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        return "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "highQuality={0}")
        fun renderers(): List<Boolean> = listOf(false, true)

        const val TIMEOUT_MS = 10_000L
        const val ROTATE = "Rotate"
    }
}
