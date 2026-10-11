package io.github.mangi.eta.validation;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic continuous animation and raw multi-pointer receiver, with no accounts or network. */
public class RealtimeProbeActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().hide();
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.preferredRefreshRate = 120;
        if (android.os.Build.VERSION.SDK_INT >= 35) attrs.setFrameRatePowerSavingsBalanced(false);
        getWindow().setAttributes(attrs);
        SharedPreferences state = getSharedPreferences("realtime_probe", MODE_PRIVATE);
        state.edit().clear().putInt("display", getDisplay().getDisplayId()).apply();
        View probe = new View(this) {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            long started, previous;
            final java.util.ArrayList<Long> intervals = new java.util.ArrayList<>();
            long frames;
            int downs, moves, ups, cancels;
            JSONArray events = new JSONArray();
            float touchX = -1, touchY = -1;
            @Override protected void onDraw(Canvas canvas) {
                long now = System.nanoTime();
                if (started == 0) started = now;
                if (previous != 0 && intervals.size() < 5000) intervals.add(now - previous);
                previous = now;
                frames++;
                canvas.drawColor(0xfff2f5f8);
                paint.setColor(0xff2255aa);
                canvas.drawRect((frames * 9) % Math.max(1, getWidth()), 160,
                    (frames * 9) % Math.max(1, getWidth()) + 90, getHeight() - 160, paint);
                paint.setTextSize(42);
                canvas.drawText("Realtime display " + getDisplay().getDisplayId(), 40, 70, paint);
                canvas.drawText("Frame " + frames, 40, 125, paint);
                paint.setColor(0xffed5544);
                if (touchX >= 0) canvas.drawCircle(touchX, touchY, 32, paint);
                if (frames % 120 == 0) {
                    long elapsed = now - started;
                    java.util.ArrayList<Long> sorted = new java.util.ArrayList<>(intervals);
                    java.util.Collections.sort(sorted);
                    long p95 = sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * 0.95)));
                    state.edit().putLong("frames", frames).putLong("animationNs", elapsed)
                        .putFloat("drawFps", (float) ((frames - 1) * 1e9 / elapsed))
                        .putFloat("drawIntervalP95Ms", p95 / 1e6f)
                        .putFloat("reportedRefreshRate", getDisplay().getRefreshRate())
                        .putInt("width", getWidth()).putInt("height", getHeight()).apply();
                }
                postInvalidateOnAnimation();
            }
            @Override public boolean onTouchEvent(MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) { downs++; events = new JSONArray(); }
                if (action == MotionEvent.ACTION_MOVE) moves++;
                if (action == MotionEvent.ACTION_UP) ups++;
                if (action == MotionEvent.ACTION_CANCEL) cancels++;
                touchX = event.getX(); touchY = event.getY();
                try {
                    if (events.length() < 512) {
                        JSONArray pointers = new JSONArray();
                        for (int i = 0; i < event.getPointerCount(); i++) pointers.put(new JSONObject()
                            .put("id", event.getPointerId(i)).put("x", event.getX(i)).put("y", event.getY(i)));
                        events.put(new JSONObject().put("action", action).put("actionIndex", event.getActionIndex())
                            .put("time", event.getEventTime()).put("downTime", event.getDownTime()).put("pointers", pointers));
                    }
                } catch (Exception failure) { throw new RuntimeException(failure); }
                SharedPreferences.Editor update = state.edit().putInt("action", action).putInt("downs", downs)
                    .putInt("moves", moves).putInt("ups", ups).putInt("cancels", cancels)
                    .putFloat("x", touchX).putFloat("y", touchY).putInt("pointerCount", event.getPointerCount())
                    .putLong("eventTime", event.getEventTime()).putLong("receivedTime", SystemClock.uptimeMillis());
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) update.putString("events", events.toString());
                update.apply();
                return true;
            }
        };
        if (android.os.Build.VERSION.SDK_INT >= 35) probe.setRequestedFrameRate(120f);
        setContentView(probe);
    }
}
