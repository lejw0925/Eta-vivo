package io.github.mangi.eta.agent.display

import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VirtualScreenMotionEventsTest {
    @Test fun rotationTransformsEveryPointerAndHistoricalSampleWithoutChangingTimeOrIds() {
        val viewport = VirtualScreenViewport.fit(720, 1600, 1600, 720)!!
        val event = motion(MotionEvent.ACTION_MOVE, listOf(4 to (600f to 200f), 9 to (300f to 700f)), 1200)
        event.addBatch(1300, arrayOf(coords(550f, 250f), coords(350f, 750f)), 0)
        try {
            val mapped = event.toVirtualDisplay(viewport)
            try {
                assertEquals(listOf(4, 9), (0 until mapped.pointerCount).map(mapped::getPointerId))
                assertEquals(1000L, mapped.downTime)
                assertEquals(1300L, mapped.eventTime)
                assertEquals(1200L, mapped.getHistoricalEventTime(0))
                assertEquals(250f, mapped.getX(0), 0.01f)
                assertEquals(170f, mapped.getY(0), 0.01f)
                assertEquals(750f, mapped.getX(1), 0.01f)
                assertEquals(370f, mapped.getY(1), 0.01f)
                assertEquals(200f, mapped.getHistoricalX(0, 0), 0.01f)
                assertEquals(120f, mapped.getHistoricalY(0, 0), 0.01f)
                assertEquals(InputDevice.SOURCE_TOUCHSCREEN, mapped.source)
            } finally { mapped.recycle() }
        } finally { event.recycle() }
    }

    @Test fun batchingRetainsCornersAndRefusesBoundaryEventsAndChangedPointerOrder() {
        val first = motion(MotionEvent.ACTION_MOVE, listOf(4 to (10f to 10f)), 1100)
        val next = motion(MotionEvent.ACTION_MOVE, listOf(4 to (80f to 10f)), 1200)
        next.addBatch(1300, arrayOf(coords(80f, 90f)), 0)
        val up = motion(MotionEvent.ACTION_UP, listOf(4 to (80f to 90f)), 1400)
        val changed = motion(MotionEvent.ACTION_MOVE, listOf(9 to (5f to 5f)), 1500)
        try {
            assertTrue(first.appendMove(next))
            assertEquals(2, first.historySize)
            assertEquals(10f, first.getHistoricalX(0), 0f)
            assertEquals(80f, first.getHistoricalX(1), 0f)
            assertEquals(10f, first.getHistoricalY(1), 0f)
            assertEquals(90f, first.y, 0f)
            assertFalse(first.appendMove(up))
            assertFalse(first.appendMove(changed))
        } finally { listOf(first, next, up, changed).forEach(MotionEvent::recycle) }
    }

    @Test fun pointerLiftOrderAndCancelledGestureCanStartANewSequence() {
        val state = VirtualScreenMotionState()
        fun accept(action: Int, ids: List<Int>, time: Long) {
            val event = motion(action, ids.map { it to (20f to 40f) }, time)
            try { assertTrue(state.accept(event)) } finally { event.recycle() }
        }
        accept(MotionEvent.ACTION_DOWN, listOf(4), 1000)
        accept(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(4, 9), 1100)
        accept(MotionEvent.ACTION_POINTER_UP, listOf(4, 9), 1200)
        assertEquals(setOf(9), state.pointerIds)
        accept(MotionEvent.ACTION_MOVE, listOf(9), 1300)
        accept(MotionEvent.ACTION_CANCEL, listOf(9), 1400)
        assertFalse(state.active)
        accept(MotionEvent.ACTION_DOWN, listOf(4), 1500)
        accept(MotionEvent.ACTION_UP, listOf(4), 1600)
        assertFalse(state.active)
    }

    @Test fun missingDownDuplicateIdsBackwardsTimeAndUnexpectedLiftAreRejected() {
        val state = VirtualScreenMotionState()
        fun check(action: Int, ids: List<Int>, time: Long, valid: Boolean) {
            val event = motion(action, ids.map { it to (20f to 40f) }, time)
            try { assertEquals(valid, state.accept(event)) } finally { event.recycle() }
        }
        check(MotionEvent.ACTION_MOVE, listOf(4), 1100, false)
        check(MotionEvent.ACTION_DOWN, listOf(4), 1200, true)
        check(MotionEvent.ACTION_MOVE, listOf(4), 1100, false)
        check(MotionEvent.ACTION_POINTER_DOWN or 256, listOf(4, 4), 1300, false)
        check(MotionEvent.ACTION_POINTER_UP, listOf(4), 1400, false)
        check(MotionEvent.ACTION_UP, listOf(9), 1500, false)
        check(MotionEvent.ACTION_UP, listOf(4), 1600, true)
    }

    private fun coords(x: Float, y: Float) = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
    private fun motion(action: Int, pointers: List<Pair<Int, Pair<Float, Float>>>, time: Long) = MotionEvent.obtain(
        1000, time, action, pointers.size,
        pointers.map { MotionEvent.PointerProperties().apply { id = it.first; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray(),
        pointers.map { coords(it.second.first, it.second.second) }.toTypedArray(), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
}
