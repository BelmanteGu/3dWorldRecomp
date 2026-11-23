package io.github.belmantegu.ds.android;

import io.github.belmantegu.ds.core.*;

import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * The design system's button (documentation/guides/DESIGN_SYSTEM.md, "Button sizes"): a plate with an
 * optional Lucide icon and always a caption (a button without text is a bug), in one of the sizes
 * DsMetrics.L / M / S. Normal: the surface plate; primary: the accent plate. The width is the content's
 * (padding, icon, gap, caption) with the size's minimum, or the parent's when the parent fixes it (buttons
 * of one group are given the same width); the caption is never cut. The view is at least 48 dp tall and
 * the plate is drawn centred in it. setOn() is the pad / keyboard focus ring.
 */
public final class GhostButton extends DsView {
    private String icon, caption = "";
    private DsMetrics.Size size = DsMetrics.S;
    private boolean primary, focused, raised;

    public GhostButton(DsKit kit) {
        super(kit);
    }

    public GhostButton(DsKit kit, DsMetrics.Size size, boolean primary) {
        super(kit);
        this.size = size;
        this.primary = primary;
    }

    public void set(String iconName, String text) {
        if (!text.equals(caption) || (iconName == null ? icon != null : !iconName.equals(icon))) {
            icon = iconName;
            caption = text;
            setContentDescription(text);
            requestLayout();
        }
        invalidate();
    }

    /** On a card or a sheet (already the surface colour): the plate is drawn one step lighter. */
    public GhostButton raised() {
        raised = true;
        return this;
    }

    /** The pad / keyboard focus ring (a dialog's or a card's focused button). */
    public void setOn(boolean on) {
        if (focused != on) {
            focused = on;
            invalidate();
        }
    }

    private float textW() {
        k.text(p, k.body, size.font, k.t.fg);
        p.setFakeBoldText(true);
        float w = p.measureText(caption);
        p.setFakeBoldText(false);
        return w;
    }

    /** The width the content wants, in px (groups use the widest as their common width). */
    public float contentWidth() {
        float w = 2 * k.cq(size.padX) + textW() + (icon != null ? k.cq(size.icon) + k.cq(DsMetrics.ICON_GAP) : 0);
        return (float) Math.ceil(Math.max(w, k.cq(size.minW))) + 1;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getMode(wSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(wSpec) : (int) contentWidth();
        setMeasuredDimension(w, k.touch(size.h));
    }

    @Override
    protected void onDraw(Canvas c) {
        GameTheme t = k.t;
        float w = getWidth(), h = Math.min(getHeight(), k.cq(size.h)), top = (getHeight() - h) / 2f;
        p.setStyle(Paint.Style.FILL);
        int fill = primary ? (isPressed() ? DsKit.alpha(t.accent, .8f) : t.accent) : (raised ? (isPressed() ? t.line : t.surface2) : (focused || isPressed() ? t.surface2 : t.surface));
        p.setColor(muted() ? DsKit.alpha(fill, .6f) : fill);
        float rad = t.radius(size);
        k.rect(c, 0, top, w, top + h, rad, p);
        if (focused) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(k.cq(DsMetrics.RING));
            p.setColor(t.focus);
            float half = k.cq(DsMetrics.RING / 2f);
            k.rect(c, half, top + half, w - half, top + h - half, rad, p);
            p.setStyle(Paint.Style.FILL);
            p.setStrokeWidth(0);
        }
        // icon + caption centred as one group
        float ic = icon != null ? k.cq(size.icon) : 0, gap = icon != null ? k.cq(DsMetrics.ICON_GAP) : 0, tw = textW();
        float x = (w - (ic + gap + tw)) / 2f;
        int fg = primary ? t.accentFg : t.fg;
        if (icon != null) {
            Icons.draw(c, icon, x, top + (h - ic) / 2f, ic, shade(primary ? fg : t.dim), p);
            x += ic + gap;
        }
        k.text(p, k.body, size.font, shade(fg));
        p.setFakeBoldText(true);
        c.drawText(caption, x, DsKit.baseline(p, top + h / 2f), p);
        p.setFakeBoldText(false);
    }
}