package io.github.mangi.eta.agent.display

import android.view.InputDevice
import android.view.MotionEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RootMotionStreamTest {
    private val info = VirtualDisplayInfo("session", "owner", 96, 720, 1280)
    @Test fun oldDetachCancellationCannotCancelANewGesture() {
        val actions = CopyOnWriteArrayList<Pair<Int, Long>>()
        val oldDown = CountDownLatch(1)
        val release = CountDownLatch(1)
        val newDown = CountDownLatch(1)
        val finished = CountDownLatch(1)
        VirtualScreenInputInjector(96, dispatch = { input, wait ->
            assertFalse(wait)
            val event = input as MotionEvent
            actions += event.actionMasked to event.downTime
            if (event.actionMasked == MotionEvent.ACTION_DOWN && event.downTime == 10L) {
                oldDown.countDown(); release.await(2, TimeUnit.SECONDS)
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN && event.downTime == 100L) newDown.countDown()
            true
        }).use { injector ->
            RootMotionStream(injector, { true }, {}, { error(it) }, { finished.countDown() }).use { stream ->
                send(stream, 1, MotionEvent.ACTION_DOWN, 10)
                assertTrue(oldDown.await(2, TimeUnit.SECONDS))
                stream.cancel()
                send(stream, 2, MotionEvent.ACTION_DOWN, 100)
                release.countDown()
                assertTrue(newDown.await(2, TimeUnit.SECONDS))
                send(stream, 2, MotionEvent.ACTION_UP, 100, 150)
                assertTrue(finished.await(2, TimeUnit.SECONDS))
                assertEquals(listOf(0 to 10L, 3 to 10L, 0 to 100L, 1 to 100L), actions.toList())
            }
        }
    }
    @Test fun lockCancelsHeldPointerEvenWithoutAnotherMotionEvent() {
        val allowed = AtomicBoolean(true)
        val down = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        VirtualScreenInputInjector(96, dispatch = { input, _ ->
            when ((input as MotionEvent).actionMasked) {
                MotionEvent.ACTION_DOWN -> down.countDown()
                MotionEvent.ACTION_CANCEL -> cancelled.countDown()
            }
            true
        }).use { injector ->
            RootMotionStream(injector, allowed::get, {}, {}).use { stream ->
                send(stream, 1, MotionEvent.ACTION_DOWN, 10)
                assertTrue(down.await(2, TimeUnit.SECONDS))
                allowed.set(false)
                ShadowSystemClock.advanceBy(Duration.ofMillis(200))
                assertTrue(cancelled.await(2, TimeUnit.SECONDS))
                assertTrue(stream.isManualControl)
            }
        }
    }
    private fun send(stream: RootMotionStream, id: Long, action: Int, down: Long, time: Long = down) {
        val event = MotionEvent.obtain(down, time, action, 30f, 40f, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { stream.enqueue(info, id, event) } finally { event.recycle() }
    }
}
