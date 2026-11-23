package io.github.belmantegu.ds.android;

import io.github.belmantegu.ds.core.*;

import android.graphics.Canvas;

/** The switch: a 5.4 x 3 unit pill, the knob left (off, dim) or right (on, accent fill). Tap flips it (the host steps the model). */
public final class Toggle extends DsView {
    private boolean on;

    public Toggle(DsKit kit) {
        super(kit);
    }

    public void setOn(boolean v) {
        if (on != v) {
            on = v;
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(Math.round(k.cq(DsMetrics.TOGGLE_W)), k.touch(DsMetrics.TOGGLE_H));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = k.cq(DsMetrics.TOGGLE_H), top = (getHeight() - h) / 2f;
        p.setStyle(android.graphics.Paint.Style.FILL);
        p.setColor(shade(on ? k.t.accent : k.t.surface2));
        k.rect(c, 0, top, w, top + h, DsMetrics.PILL, p);
        float d = k.cq(DsMetrics.TOGGLE_KNOB), m = k.cq(DsMetrics.TOGGLE_INSET);
        p.setColor(shade(on ? k.t.accentFg : k.t.dim));
        float x = on ? w - m - d : m;
        c.drawCircle(x + d / 2f, top + h / 2f, d / 2f, p);
        if (isPressed()) {
            p.setColor(GameTheme.PRESS);
            k.rect(c, 0, top, w, top + h, DsMetrics.PILL, p);
        }
    }
}
