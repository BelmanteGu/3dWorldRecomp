package io.github.belmantegu.ds.android;

import io.github.belmantegu.ds.core.*;

import android.graphics.Canvas;
import android.view.MotionEvent;

/**
 * The slider control: a flat groove (surface2) with the accent fill and a round knob, at most 28 units
 * long, and the value in a small plate at its right. Touch drags it; the parent does not scroll while a
 * finger is on it. The progress runs 0..max in whole steps.
 */
public final class SliderRow extends DsView {
    public interface OnChange {
        void changed(int progress);
    }

    private int max = 100, progress;
    private String value = "";
    private OnChange listener;

    public SliderRow(DsKit kit) {
        super(kit);
    }

    public void setOnChange(OnChange l) { listener = l; }

    public void setMax(int m) {
        max = Math.max(1, m);
        invalidate();
    }

    public void setProgress(int v) {
        progress = Math.max(0, Math.min(max, v));
        invalidate();
    }

    public void setValue(String s) {
        if (!s.equals(value)) {
            value = s;
            invalidate();
        }
    }

    private float chipW() {
        k.text(p, k.body, DsMetrics.T_DESC, k.t.fg);
        p.setFakeBoldText(true);
        float w = p.measureText(value.isEmpty() ? "000%" : value);
        p.setFakeBoldText(false);
        return Math.max(w, p.measureText("000%")) + 2 * k.cq(DsMetrics.SLIDER_CHIP_PAD);
    }

    /** The groove's left and right ends. */
    private float left() { return k.cq(DsMetrics.SLIDER_PAD); }

    private float right() {
        float full = getWidth() - chipW() - k.cq(DsMetrics.SLIDER_CHIP_GAP);
        return Math.max(left() + k.cq(DsMetrics.SLIDER_MIN), Math.min(k.cq(DsMetrics.SLIDER_TRACK_W), full) - k.cq(DsMetrics.SLIDER_PAD));
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        float w = k.cq(DsMetrics.SLIDER_TRACK_W) + k.cq(DsMetrics.SLIDER_CHIP_GAP) + chipW();
        int avail = MeasureSpec.getSize(wSpec);
        if (MeasureSpec.getMode(wSpec) != MeasureSpec.UNSPECIFIED && avail > 0) w = Math.min(w, avail);
        setMeasuredDimension(Math.round(w), k.touch(DsMetrics.CONTROL_H));
    }

    @Override
    protected void onDraw(Canvas c) {
        float h = getHeight(), cy = h / 2f, l = left(), r = right(), g = k.cq(DsMetrics.SLIDER_TRACK_H), kn = k.cq(DsMetrics.SLIDER_KNOB) / 2f;
        float x = l + (r - l) * progress / max;
        p.setStyle(android.graphics.Paint.Style.FILL);
        p.setColor(shade(k.t.surface2));
        k.rect(c, l, cy - g / 2f, r, cy + g / 2f, DsMetrics.PILL, p);
        p.setColor(shade(k.t.accent));
        k.rect(c, l, cy - g / 2f, Math.max(l + g, x), cy + g / 2f, DsMetrics.PILL, p);
        p.setColor(shade(k.t.fg));
        c.drawCircle(x, cy, kn, p);
        // the value plate
        float cw = chipW(), ch = k.cq(DsMetrics.SLIDER_CHIP_H), cl = Math.min(getWidth() - cw, r + k.cq(DsMetrics.SLIDER_PAD) + k.cq(DsMetrics.SLIDER_CHIP_GAP));
        p.setColor(shade(k.t.surface));
        k.rect(c, cl, cy - ch / 2f, cl + cw, cy + ch / 2f, k.t.rSm, p);
        k.text(p, k.body, DsMetrics.T_DESC, shade(k.t.fg));
        p.setFakeBoldText(true);
        c.drawText(value, cl + (cw - p.measureText(value)) / 2f, DsKit.baseline(p, cy), p);
        p.setFakeBoldText(false);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (muted()) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE: {
                float l = left(), w = Math.max(1f, right() - l);
                int v = Math.round(Math.max(0f, Math.min(1f, (e.getX() - l) / w)) * max);
                if (v != progress) {
                    progress = v;
                    invalidate();
                    if (listener != null) listener.changed(v);
                }
                return true;
            }
            default:
                return true;
        }
    }
}
