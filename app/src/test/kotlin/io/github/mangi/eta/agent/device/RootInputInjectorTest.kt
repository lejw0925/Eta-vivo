package io.github.mangi.eta.agent.device

import android.view.InputDevice
import android.view.MotionEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RootInputInjectorTest {
    private data class Touch(val action: Int, val x: Float, val y: Float, val downTime: Long, val time: Long)
    private val path = listOf(SwipePath.Point(20, 20), SwipePath.Point(120, 20),
        SwipePath.Point(120, 70), SwipePath.Point(20, 20))

    @Test
    fun polylineIsOneTouchWithOrderedVerticesAndTouchscreenSource() {
        val events = CopyOnWriteArrayList<Touch>()
        val clock = AtomicLong(1_000)
        RootInputInjector(0, dispatch = { input, wait ->
            val event = input as MotionEvent
            assertTrue(wait)
            assertEquals(InputDevice.SOURCE_TOUCHSCREEN, event.source)
            assertEquals(1, event.pointerCount)
            events += Touch(event.action, event.x, event.y, event.downTime, event.eventTime)
            true
        }, uptimeMillis = clock::get, pause = { clock.addAndGet(it) }).use { injector ->
            val result = injector.executePath(path, 800)
            assertTrue(result.getBoolean("ok"))
            assertEquals("unconfirmed", result.getString("effect"))
        }
        assertEquals(1, events.count { it.action == MotionEvent.ACTION_DOWN })
        assertEquals(1, events.count { it.action == MotionEvent.ACTION_UP })
        assertEquals(0, events.count { it.action == MotionEvent.ACTION_CANCEL })
        assertTrue(events.all { it.downTime == 1_000L })
        var position = 0
        path.drop(1).forEach { point ->
            val next = events.indexOfFirstFrom(position) { it.action == MotionEvent.ACTION_MOVE && it.x == point.x.toFloat() && it.y == point.y.toFloat() }
            assertTrue("missing vertex $point", next >= position)
            position = next + 1
        }
        assertEquals(path.last().x.toFloat(), events.last().x)
        assertTrue(events.last().time in 1_800..1_808)
        // The first segment is 100px of ~262px, so it consumes ~305ms of the total 800ms.
        val firstCorner = events.first { it.x == 120f && it.y == 20f }
        assertTrue(firstCorner.time - firstCorner.downTime in 300..312)
    }

    @Test
    fun delayedDispatchStillVisitsEveryVertex() {
        val visited = CopyOnWriteArrayList<Pair<Float, Float>>()
        val clock = AtomicLong(1_000)
        RootInputInjector(96, dispatch = { input, _ ->
            val event = input as MotionEvent
            if (event.action == MotionEvent.ACTION_DOWN) clock.addAndGet(1_000)
            if (event.action == MotionEvent.ACTION_MOVE) visited += event.x to event.y
            true
        }, uptimeMillis = clock::get, pause = { clock.addAndGet(it) }).use {
            assertTrue(it.executePath(path, 100).getBoolean("ok"))
        }
        assertEquals(path.drop(1).map { it.x.toFloat() to it.y.toFloat() }, visited.toList())
    }

    @Test
    fun failedMoveCancelsAtLastPositionWithoutReplayingOrCompleting() {
        val events = CopyOnWriteArrayList<Touch>()
        val clock = AtomicLong(1_000)
        RootInputInjector(96, dispatch = { input, wait ->
            val event = input as MotionEvent
            events += Touch(event.action, event.x, event.y, event.downTime, event.eventTime)
            if (event.action == MotionEvent.ACTION_CANCEL) assertFalse(wait)
            event.action != MotionEvent.ACTION_MOVE || event.x < 100
        }, uptimeMillis = clock::get, pause = { clock.addAndGet(it) }).use {
            val result = it.executePath(path, 500)
            assertFalse(result.getBoolean("ok"))
            assertEquals("INPUT_DISPATCH_UNCONFIRMED", result.getString("code"))
        }
        assertEquals(1, events.count { it.action == MotionEvent.ACTION_DOWN })
        assertEquals(0, events.count { it.action == MotionEvent.ACTION_UP })
        assertEquals(MotionEvent.ACTION_CANCEL, events.last().action)
        assertEquals(events[events.size - 2].x, events.last().x)
        assertEquals(events[events.size - 2].y, events.last().y)
    }

    @Test
    fun timedOutPathCancelsTheExistingPointerWithoutReplay() {
        val actions = CopyOnWriteArrayList<Int>()
        val cancelled = CountDownLatch(1)
        RootInputInjector(96, inputTimeoutMs = 1_000, dispatch = { input, wait ->
            val action = (input as MotionEvent).action
            actions += action
            when (action) {
                MotionEvent.ACTION_MOVE -> CountDownLatch(1).await()
                MotionEvent.ACTION_CANCEL -> { assertFalse(wait); cancelled.countDown() }
            }
            true
        }).use {
            val result = it.executePath(path, 500)
            assertEquals("UI_INPUT_TIMEOUT", result.getString("code"))
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        }
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL), actions.toList())
    }

    @Test
    fun invalidRequestsNeverStartTouch() {
        RootInputInjector(96, dispatch = { _, _ -> error("invalid paths must not inject") }).use { injector ->
            listOf(emptyList(), path.take(1), List(65) { path.first() }, path + SwipePath.Point(-1, 2)).forEach {
                assertThrows(IllegalArgumentException::class.java) { injector.executePath(it, 500) }
            }
            listOf(99, 2001).forEach {
                assertThrows(IllegalArgumentException::class.java) { injector.executePath(path, it) }
            }
        }
        assertThrows(IllegalArgumentException::class.java) { RootInputInjector(-1) }
    }

    private fun <T> List<T>.indexOfFirstFrom(start: Int, predicate: (T) -> Boolean): Int =
        (start until size).firstOrNull { predicate(this[it]) } ?: -1
}
