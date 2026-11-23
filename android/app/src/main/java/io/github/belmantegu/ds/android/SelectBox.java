package io.github.belmantegu.ds.android;

import io.github.belmantegu.ds.core.*;

import android.graphics.Canvas;

/**
 * The selector: a surface plate with an optional leading icon, the current value and a chevron. A tap
 * moves to the next value (the host steps the model, wrapping), so the chevron is the cue that it changes.
 * The width follows the value (min 16 units); the value is never cut.
 */
public final class SelectBox extends DsView {
    private String value = "", icon;

    public SelectBox(DsKit kit) {
        super(kit);
    }

    /** A leading icon (the language, the driver), or null. */
    public void setIcon(String name) {
        icon = name;
        requestLayout();
    }

    public void setValue(String v) {
        if (!v.equals(value)) {
            value = v;
            requestLayout();
        }
        invalidate();
    }

    private float textW() {
        k.text(p, k.body, DsMetrics.T_BODY, k.t.fg);
        p.setFakeBoldText(true);
        float w = p.measureText(value);
        p.setFakeBoldText(false);
        return w;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        float w = 2 * k.cq(DsMetrics.SELECT_PAD) + textW() + k.cq(DsMetrics.SELECT_ARROW_GAP) + k.cq(DsMetrics.SELECT_ARROW)
                + (icon != null ? k.cq(DsMetrics.SELECT_LEAD) + k.cq(DsMetrics.SELECT_LEAD_GAP) : 0);
        setMeasuredDimension((int) Math.ceil(Math.max(w, k.cq(DsMetrics.SELECT_MIN_W))) + 1, k.touch(DsMetrics.CONTROL_H));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = Math.min(getHeight(), k.cq(DsMetrics.CONTROL_H)), top = (getHeight() - h) / 2f;
        p.setStyle(android.graphics.Paint.Style.FILL);
        p.setColor(isPressed() ? k.t.surface2 : k.t.surface);
        if (muted()) p.setColor(DsKit.alpha(p.getColor(), .6f));
        k.rect(c, 0, top, w, top + h, k.t.rSm, p);
        float x = k.cq(DsMetrics.SELECT_PAD), ic = k.cq(DsMetrics.SELECT_ARROW);
        if (icon != null) {
            float lead = k.cq(DsMetrics.SELECT_LEAD);
            Icons.draw(c, icon, x, top + (h - lead) / 2f, lead, shade(k.t.dim), p);
            x += lead + k.cq(DsMetrics.SELECT_LEAD_GAP);
        }
        k.text(p, k.body, DsMetrics.T_BODY, shade(k.t.fg));
        p.setFakeBoldText(true);
        c.drawText(value, x, DsKit.baseline(p, top + h / 2f), p);
        p.setFakeBoldText(false);
        Icons.draw(c, "chevron-down", w - k.cq(DsMetrics.SELECT_PAD) - ic, top + (h - ic) / 2f, ic, shade(k.t.dim), p);
    }
}