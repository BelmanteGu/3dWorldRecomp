package io.github.belmantegu.ds.android;

import io.github.belmantegu.ds.core.*;

import android.graphics.Canvas;

/**
 * Icon-only button (the header's Back): a square plate with the theme's medium radius and a centred
 * icon, 4.6 units, inside a view that is at least 48 dp (size M of the scale). Needs a content description (it has no caption).
 */
public final class IconButton extends DsView {
    private final String icon;
    private boolean focused;

    public IconButton(DsKit kit, String icon, String description) {
        super(kit);
        this.icon = icon;
        setContentDescription(description);
    }

    /** The pad's focus ring. */
    public void setFocusedLook(boolean on) {
        if (focused != on) {
            focused = on;
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int w, int h) {
        int s = k.touch(DsMetrics.M.h);
        setMeasuredDimension(s, s);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), s = Math.min(Math.min(w, h), k.cq(DsMetrics.M.h));
        float l = (w - s) / 2f, t = (h - s) / 2f;
        p.setStyle(android.graphics.Paint.Style.FILL);
        p.setColor(isPressed() ? k.t.surface2 : k.t.surface);
        k.rect(c, l, t, l + s, t + s, k.t.rMd, p);
        float is = k.cq(DsMetrics.M.icon);
        Icons.draw(c, icon, (w - is) / 2f, (h - is) / 2f, is, k.t.fg, p);
        if (focused) {
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, k.cq(DsMetrics.RING)));
            p.setColor(k.t.focus);
            float in = p.getStrokeWidth() / 2f;
            k.rect(c, l + in, t + in, l + s - in, t + s - in, k.t.rMd, p);
            p.setStyle(android.graphics.Paint.Style.FILL);
        }
    }
}
