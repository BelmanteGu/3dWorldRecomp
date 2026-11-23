package io.github.belmantegu.ds.android;

import io.github.belmantegu.ds.core.*;

import android.graphics.Canvas;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * The segmented control: a surface plate with the theme's medium radius holding two to four labels, the
 * selected one on the accent colour. Tap a segment to pick it. If the labels do not fit the width the
 * text and paddings shrink together, so nothing is cut.
 */
public final class Segmented extends DsView {
    public interface OnPick {
        void pick(int index);
    }

    private final List<String> labels = new ArrayList<>();
    private int on;
    private OnPick listener;
    private float scale = 1f;

    public Segmented(DsKit kit) {
        super(kit);
    }

    public void setOnPick(OnPick l) { listener = l; }

    public void set(List<String> items, int selected) {
        if (!items.equals(labels)) {
            labels.clear();
            labels.addAll(items);
            requestLayout();
        }
        if (on != selected) {
            on = selected;
        }
        invalidate();
    }

    private float segW(int i) {
        k.text(p, k.body, DsMetrics.T_DESC * scale, k.t.fg);
        return p.measureText(labels.get(i)) + 2 * k.cq(DsMetrics.SEG_PAD) * scale;
    }

    private float natural() {
        scale = 1f;
        float w = 2 * k.cq(DsMetrics.SEG_INSET) + Math.max(0, labels.size() - 1) * k.cq(DsMetrics.SEG_GAP);
        for (int i = 0; i < labels.size(); ++i) w += segW(i);
        return w;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        float w = natural();
        int mode = MeasureSpec.getMode(wSpec), avail = MeasureSpec.getSize(wSpec);
        if (mode != MeasureSpec.UNSPECIFIED && w > avail && avail > 0) {
            scale = avail / w;
            w = avail;
        }
        setMeasuredDimension(Math.round(w), k.touch(DsMetrics.CONTROL_H));
    }

    private float plateH() { return Math.min(getHeight(), k.cq(DsMetrics.CONTROL_H)); }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = plateH(), top = (getHeight() - h) / 2f;
        p.setStyle(android.graphics.Paint.Style.FILL);
        p.setColor(shade(k.t.surface));
        k.rect(c, 0, top, w, top + h, k.t.rMd, p);
        float x = k.cq(DsMetrics.SEG_INSET) * 1f, gap = k.cq(DsMetrics.SEG_GAP), in = k.cq(DsMetrics.SEG_INSET);
        for (int i = 0; i < labels.size(); ++i) {
            float sw = segW(i);
            boolean sel = i == on;
            if (sel) {
                p.setColor(shade(k.t.accent));
                k.rect(c, x, top + in, x + sw, top + h - in, k.t.rSm, p);
            }
            k.text(p, k.body, DsMetrics.T_DESC * scale, shade(sel ? k.t.accentFg : k.t.dim));
            c.drawText(labels.get(i), x + (sw - p.measureText(labels.get(i))) / 2f, DsKit.baseline(p, top + h / 2f), p);
            x += sw + gap;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean r = super.onTouchEvent(e);
        if (e.getActionMasked() == MotionEvent.ACTION_UP && listener != null) {
            float x = k.cq(DsMetrics.SEG_INSET), ex = e.getX();
            for (int i = 0; i < labels.size(); ++i) {
                float sw = segW(i);
                if (ex < x + sw + k.cq(DsMetrics.SEG_HIT) || i == labels.size() - 1) {
                    listener.pick(i);
                    break;
                }
                x += sw + k.cq(DsMetrics.SEG_GAP);
            }
        }
        return r;
    }
}
