package io.github.mangi.eta.agent.display

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class VirtualScreenDisplayViewTest {
    private val display = VirtualDisplayInfo("screen", "owner", 4, 720, 1280)

    @Test fun openingTouchAndEmptyOrOutsideFrameDoNotTakeOverTheTask() {
        val view = VirtualScreenDisplayView(RuntimeEnvironment.getApplication(), "viewer")
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2000, android.view.View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1000, 2000)
        view.setTarget(display, null)
        view.setResumed(true)
        var takeovers = 0
        view.onManualDown = { takeovers++; true }
        val openingDown = SystemClock.uptimeMillis()
        touch(view, MotionEvent.ACTION_DOWN, openingDown, 500f, 1000f)
        assertEquals(0, takeovers)
        ShadowSystemClock.advanceBy(Duration.ofMillis(10))
        view.setFrame(Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888), display)
        touch(view, MotionEvent.ACTION_DOWN, openingDown, 500f, 1000f)
        touch(view, MotionEvent.ACTION_UP, openingDown, 500f, 1000f)
        assertEquals(0, takeovers)
        touch(view, MotionEvent.ACTION_DOWN, SystemClock.uptimeMillis(), 500f, 10f)
        assertEquals(0, takeovers)
        touch(view, MotionEvent.ACTION_DOWN, SystemClock.uptimeMillis(), 500f, 1000f)
        assertEquals(1, takeovers)
        view.setResumed(false)
        view.clearFrame()
    }

    @Test fun eachFreshGestureTakesOverAndSuspendedOrStaleGeometryCannot() {
        val view = VirtualScreenDisplayView(RuntimeEnvironment.getApplication(), "viewer")
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2000, android.view.View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1000, 2000)
        view.setTarget(display, null)
        view.setResumed(true)
        view.setFrame(Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888), display)
        var takeovers = 0
        var inputs = 0
        view.onManualDown = { takeovers++; true }
        view.onInput = { _, _ -> inputs++ }
        repeat(2) {
            val now = SystemClock.uptimeMillis()
            touch(view, MotionEvent.ACTION_DOWN, now, 500f, 1000f)
            touch(view, MotionEvent.ACTION_UP, now, 500f, 1000f)
        }
        assertEquals(2, takeovers)
        assertEquals(2, inputs)
        view.setResumed(false)
        touch(view, MotionEvent.ACTION_DOWN, SystemClock.uptimeMillis(), 500f, 1000f)
        assertEquals(2, takeovers)
        view.setResumed(true)
        val replacement = display.withGeometry(1280, 720, 1)
        view.setTarget(replacement, null)
        view.setFrame(Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888), display)
        touch(view, MotionEvent.ACTION_DOWN, SystemClock.uptimeMillis(), 500f, 1000f)
        assertEquals(2, takeovers)
        view.setResumed(false)
        view.clearFrame()
    }

    private fun touch(view: VirtualScreenDisplayView, action: Int, downTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }
}
