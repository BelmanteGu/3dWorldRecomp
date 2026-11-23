package io.github.belmantegu.ds.android;

import io.github.belmantegu.ds.core.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.Locale;

/**
 * The FPS counter over the game (Options, Video, "FPS counter"; off by default). Simple: a pill with a
 * status dot (green, yellow, red against the frame rate the game is aiming at), the number in bold and a
 * dim "fps". Full: FPS, frame time, 1% low, GPU time and a small graph of the last windows. Drag it
 * anywhere (the place is kept); a tap switches between simple and full.
 *
 * The numbers come from the game's own frame log (FpsStats: the [perf] and [pace] lines of
 * files/native.log, one pair every 2 s), read once a second by a small worker thread that runs only
 * while the counter is shown. Drawing allocates nothing: the texts are formatted when a window arrives,
 * the paints and the rectangle are fields, and the view repaints only then or while it is dragged.
 */
public final class FpsOverlay extends View {
    /** Called when a tap changed the mode, so the setting can be saved (the mode is the new one). */
    public interface OnMode {
        void onMode(int mode);
    }

    private static final String POS = "fps_overlay_pos";
    private final String[] values = new String[4];

    private final DsKit k;
    private final FpsStats stats = new FpsStats();
    private final TextSource source;
    private final String[] labels;
    private final SharedPreferences prefs;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final OnMode onMode;
    private int mode, limit;
    private volatile boolean running;
    private Thread worker;
    // formatted when a window arrives
    private String number = "--", frame = "--", low = "--", gpu = "--";
    private int level = FpsStats.GOOD;
    private float downX, downY, startX, startY;
    private boolean dragging;

    /** Where the game's frame log text comes from (the tail of its native.log). */
    public interface TextSource {
        String read();
    }

    /** `labels`: the unit ("fps"), "Frame", "1% low" and "GPU" in the player's language; `description` names the view. */
    public FpsOverlay(DsKit kit, TextSource source, String[] labels, String description, OnMode onMode) {
        super(kit.context);
        this.k = kit;
        this.source = source;
        this.labels = labels;
        this.onMode = onMode;
        this.prefs = kit.context.getSharedPreferences("ds_fps_overlay", Context.MODE_PRIVATE);
        setVisibility(GONE);
        setClickable(true);
        setFocusable(false);
        setContentDescription(description);
    }

    /** 0 off, 1 simple, 2 full. */
    public void setMode(int m) {
        if (m == mode) return;
        mode = m;
        setVisibility(m == 0 ? GONE : VISIBLE);
        requestLayout();
        invalidate();
        if (m == 0) stop();
        else if (isAttachedToWindow()) start();
    }

    /** The app's frame limit (0 auto, 30, 60): what the dot is judged against. */
    public void setLimit(int fpsLimit) {
        limit = fpsLimit;
        refreshTexts();
        invalidate();
    }

    // ---- Reading ----

    private void start() {
        if (running) return;
        running = true;
        worker = new Thread(() -> {
            while (running) {
                String tail = source.read();
                boolean changed;
                synchronized (stats) {
                    changed = stats.feed(tail);
                    if (changed) format();
                }
                if (changed) postInvalidate();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "fps-counter");
        worker.setDaemon(true);
        worker.start();
    }

    private void stop() {
        running = false;
        if (worker != null) worker.interrupt();
        worker = null;
    }

    private void refreshTexts() {
        synchronized (stats) {
            if (stats.has) format();
        }
    }

    // Under the stats lock.
    private void format() {
        number = String.format(Locale.US, "%.0f", stats.fps);
        frame = String.format(Locale.US, "%.1f ms", stats.avgMs);
        low = stats.p99Ms > 0 ? String.format(Locale.US, "%.0f", stats.low()) : "--";
        gpu = stats.gpuMs > 0 ? String.format(Locale.US, "%.1f ms", stats.gpuMs) : "--";
        level = FpsStats.level(stats.fps, limit);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mode != 0) start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    // ---- Layout and drawing ----

    private int levelColor() {
        return level == FpsStats.GOOD ? k.t.ok : level == FpsStats.FAIR ? GameTheme.CAUTION : GameTheme.CRITICAL;
    }

    private float rowW() {
        // the widest line of the full card: its label, a gap and the longest value
        k.text(p, k.body, DsMetrics.T_DESC, k.t.fg);
        p.setFakeBoldText(true);
        float v = Math.max(Math.max(p.measureText("00.0 ms"), p.measureText(frame)), p.measureText(gpu));
        p.setFakeBoldText(false);
        k.text(p, k.body, DsMetrics.T_DESC, k.t.dim);
        float l = Math.max(Math.max(p.measureText(labels[0]), p.measureText(labels[1])),
                Math.max(p.measureText(labels[2]), p.measureText(labels[3])));
        return l + k.cq(DsMetrics.FPS_LABEL_GAP) + v;
    }


    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        if (mode == 2) {
            float w = Math.max(k.cq(DsMetrics.FPS_CARD_MIN_W), rowW()) + 2 * k.cq(DsMetrics.FPS_CARD_PAD);
            float h = 4 * k.cq(DsMetrics.FPS_ROW_H) + k.cq(DsMetrics.FPS_GRAPH_GAP) + k.cq(DsMetrics.FPS_GRAPH_H) + 2 * k.cq(DsMetrics.FPS_CARD_TOP);
            setMeasuredDimension((int) Math.ceil(w), (int) Math.ceil(h));
        } else {
            k.text(p, k.body, DsMetrics.T_BODY, k.t.fg);
            p.setFakeBoldText(true);
            float n = Math.max(p.measureText(number), p.measureText("00"));
            p.setFakeBoldText(false);
            k.text(p, k.body, DsMetrics.T_DESC, k.t.dim);
            float u = p.measureText(labels[0]);
            float w = k.cq(DsMetrics.FPS_PAD) + 2 * k.cq(DsMetrics.FPS_DOT) + k.cq(DsMetrics.FPS_DOT_GAP) + n + k.cq(DsMetrics.FPS_UNIT_GAP) + u + k.cq(DsMetrics.FPS_CARD_PAD);
            setMeasuredDimension((int) Math.ceil(w), (int) Math.ceil(k.cq(DsMetrics.FPS_PILL_H)));
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        synchronized (stats) {
            float w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.FILL);
            p.setColor(GameTheme.SHADE);
            box.set(0, 0, w, h);
            float rad = Math.min(k.cq(mode == 2 ? k.t.rMd : k.t.rSm), h / 2f);
            c.drawRoundRect(box, rad, rad, p);
            if (mode == 2) drawFull(c, w, h);
            else drawSimple(c, w, h);
        }
    }

    private void drawSimple(Canvas c, float w, float h) {
        float cy = h / 2f, x = k.cq(DsMetrics.FPS_PAD), dot = k.cq(DsMetrics.FPS_DOT);
        p.setColor(levelColor());
        c.drawCircle(x + dot, cy, dot, p);
        x += 2 * dot + k.cq(DsMetrics.FPS_DOT_GAP);
        k.text(p, k.body, DsMetrics.T_BODY, k.t.fg);
        p.setFakeBoldText(true);
        c.drawText(number, x, DsKit.baseline(p, cy), p);
        x += p.measureText(number) + k.cq(DsMetrics.FPS_UNIT_GAP);
        p.setFakeBoldText(false);
        k.text(p, k.body, DsMetrics.T_DESC, k.t.dim);
        c.drawText(labels[0], x, DsKit.baseline(p, cy), p);
    }

    private void drawFull(Canvas c, float w, float h) {
        float pad = k.cq(DsMetrics.FPS_CARD_PAD), rowH = k.cq(DsMetrics.FPS_ROW_H), y = k.cq(DsMetrics.FPS_CARD_TOP);
        values[0] = number;
        values[1] = frame;
        values[2] = low;
        values[3] = gpu;
        for (int i = 0; i < 4; ++i) {
            float cy = y + rowH / 2f;
            k.text(p, k.body, DsMetrics.T_DESC, k.t.dim);
            if (i == 0) {
                p.setColor(levelColor());
                c.drawCircle(pad + k.cq(DsMetrics.FPS_DOT), cy, k.cq(DsMetrics.FPS_DOT), p);
                k.text(p, k.body, DsMetrics.T_DESC, k.t.dim);
                c.drawText(labels[0].toUpperCase(java.util.Locale.ROOT), pad + k.cq(DsMetrics.FPS_LABEL_INDENT), DsKit.baseline(p, cy), p);
            } else {
                c.drawText(labels[i], pad, DsKit.baseline(p, cy), p);
            }
            k.text(p, k.body, DsMetrics.T_DESC, k.t.fg);
            p.setFakeBoldText(true);
            c.drawText(values[i], w - pad - p.measureText(values[i]), DsKit.baseline(p, cy), p);
            p.setFakeBoldText(false);
            y += rowH;
        }
        // the graph: one bar per window, the scale to the larger of 60 and the highest sample
        float gh = k.cq(DsMetrics.FPS_GRAPH_H), gTop = y + k.cq(DsMetrics.FPS_GRAPH_GAP), gw = w - 2 * pad;
        float max = 60f;
        for (int i = 0; i < stats.count; ++i) max = Math.max(max, stats.at(i));
        float bar = gw / FpsStats.HISTORY, gap = Math.min(k.cq(DsMetrics.FPS_BAR_GAP), bar / 3f);
        for (int i = 0; i < stats.count; ++i) {
            float v = stats.at(i);
            int lv = FpsStats.level(v, limit);
            p.setColor(DsKit.alpha(lv == FpsStats.GOOD ? k.t.ok : lv == FpsStats.FAIR ? GameTheme.CAUTION : GameTheme.CRITICAL, .85f));
            float bh = Math.max(k.cq(DsMetrics.FPS_BAR_MIN), gh * Math.min(1f, v / max));
            float left = pad + (FpsStats.HISTORY - stats.count + i) * bar;
            c.drawRect(left, gTop + gh - bh, left + bar - gap, gTop + gh, p);
        }
    }

    // ---- Touch: drag it, tap to switch ----

    private FrameLayout.LayoutParams lp() {
        return (FrameLayout.LayoutParams) getLayoutParams();
    }

    /** Puts the counter where it was left (or at the top left on first use). Call once it is in the layout. */
    public void restorePosition() {
        FrameLayout.LayoutParams l = lp();
        l.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        String saved = prefs.getString(POS, "");
        int x = k.gap.left + Math.round(k.cq(DsMetrics.CORNER)), y = k.gap.top + Math.round(k.cq(DsMetrics.CORNER));
        if (saved != null && saved.contains(",")) {
            try {
                String[] xy = saved.split(",");
                x = Integer.parseInt(xy[0]);
                y = Integer.parseInt(xy[1]);
            } catch (NumberFormatException ignored) {
                // keep the default
            }
        }
        l.leftMargin = x;
        l.topMargin = y;
        setLayoutParams(l);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        FrameLayout.LayoutParams l = lp();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                startX = l.leftMargin;
                startY = l.topMargin;
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                float slop = k.cq(DsMetrics.FPS_SLOP);
                if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) dragging = true;
                if (dragging) {
                    View parent = (View) getParent();
                    int maxX = Math.max(0, parent.getWidth() - getWidth()), maxY = Math.max(0, parent.getHeight() - getHeight());
                    l.leftMargin = Math.max(0, Math.min(maxX, Math.round(startX + dx)));
                    l.topMargin = Math.max(0, Math.min(maxY, Math.round(startY + dy)));
                    setLayoutParams(l);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    prefs.edit().putString(POS, l.leftMargin + "," + l.topMargin).apply();
                } else {
                    setMode(mode == 1 ? 2 : 1);
                    if (onMode != null) onMode.onMode(mode);
                }
                dragging = false;
                return true;
            default:
                return true;
        }
    }

    /** The view's layout parameters for the game's layout. */
    public static ViewGroup.LayoutParams params() {
        return new FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP | android.view.Gravity.START);
    }
}
