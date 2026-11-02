package reikai.presentation.reader

import android.view.InputDevice
import android.view.MotionEvent

/**
 * Whether a mouse wheel's [event] turns forward, null for any other event. Paged novels read it, where a wheel has
 * no column to scroll. Twin of `mouseWheelPaging`, pinned by MouseWheelConformanceTest.
 */
fun wheelForward(event: MotionEvent): Boolean? {
    if (event.source and InputDevice.SOURCE_CLASS_POINTER == 0 || event.action != MotionEvent.ACTION_SCROLL) return null
    val scroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
    return if (scroll == 0f) null else scroll < 0f
}
