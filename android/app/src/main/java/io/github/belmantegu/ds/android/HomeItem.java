package io.github.belmantegu.ds.android;

import io.github.belmantegu.ds.core.*;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Locale;

/**
 * A menu item of the home screen and of the in-game sheet (documentation/guides/DESIGN_SYSTEM.md), size L
 * of the button scale: a Lucide icon, the label in the title face and, on the right, an optional small hint
 * (".zip", or why the item is locked). Idle it is a translucent surface plate; selected it is the theme's
 * gradient (GameTheme.selA..selC) with a bar at the left edge (the theme's `bar`, none when 0) and a thin
 * light line along the inside of its border. Everything is clipped to the plate: nothing glows outside it.
 * No frame, no arrows. The label is never cut: when it does not fit, its size shrinks. The view is as tall
 * as the plate (at least 48 dp), so the menu's items all have the same height; the gap between items is
 * the parent's (DsMetrics.S1).
 */
public final class HomeItem extends DsView {
    private final String icon, label;
    private String hint;
    private boolean on, locked;
    private float plateUnits = DsMetrics.L.h;
    private final RectF box = new RectF();
    private final Path clip = new Path();
    private LinearGradient fill;
    private int fillWidth = -1;

    public HomeItem(DsKit kit, String icon, String label, String hint) {
        super(kit);
        this.icon = icon;
        this.label = kit.t.upperTitle ? label.toUpperCase(Locale.ROOT) : label;
        this.hint = hint;
        setContentDescription(label);
    }

    public void setOn(boolean value) {
        if (on != value) {
            on = value;
            invalidate();
        }
    }

    public boolean isOn() { return on; }

    /** A plate shorter (or taller) than size L's, in layout units: the split home's menu uses 6.2 so five items fit. */
    public HomeItem plateHeight(float units) {
        plateUnits = units;
        requestLayout();
        return this;
    }

    /** A locked item (Play without the game): lock icon, muted, with the reason on the right. Still tappable (it says why). */
    public void setLocked(boolean value, String reason) {
        locked = value;
        hint = value ? reason : "";
        setContentDescription(label + (value ? ", " + reason : ""));
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        // an exact height (the split menu sharing out its room) wins over the touch-target minimum
        int h = MeasureSpec.getMode(hSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(hSpec) : k.touch(plateUnits);
        setMeasuredDimension(MeasureSpec.getSize(wSpec), h);
    }

    @Override
    protected void onDraw(Canvas c) {
        GameTheme t = k.t;
        DsMetrics.Size sz = DsMetrics.L;
        float w = getWidth(), h = getHeight();
        float ph = Math.min(h, k.cq(plateUnits)), top = (h - ph) / 2f;
        float rad = Math.min(k.cq(t.radius(sz)), ph / 2f);
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        box.set(0, top, w, top + ph);
        clip.reset();
        clip.addRoundRect(box, rad, rad, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        if (on) {
            if (fill == null || fillWidth != (int) w) {
                fillWidth = (int) w;
                fill = new LinearGradient(0, 0, w, 0, new int[] {t.selA, t.selB, t.selC}, new float[] {0, .6f, 1}, Shader.TileMode.CLAMP);
            }
            p.setShader(fill);
            p.setColor(GameTheme.WHITE);
            c.drawRect(box, p);
            p.setShader(null);
            // the light inside the border: a thin line, clipped with the plate
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(k.cq(DsMetrics.ITEM_LINE));
            p.setColor(GameTheme.GLINT);
            c.drawPath(clip, p);
            p.setStyle(Paint.Style.FILL);
            p.setStrokeWidth(0);
            if (t.bar != 0) {
                p.setColor(t.bar);
                c.drawRect(0, top, k.cq(DsMetrics.ITEM_BAR), top + ph, p);
            }
        } else {
            p.setColor(DsKit.alpha(isPressed() ? t.surface2 : t.surface, .82f));
            c.drawRect(box, p);
        }
        c.restore();
        int fg = on ? t.accentFg : t.fg;
        if (locked) fg = DsKit.alpha(fg, .6f);
        float cy = top + ph / 2f, x = k.cq(sz.padX), ic = k.cq(sz.icon);
        Icons.draw(c, locked ? "lock" : icon, x, cy - ic / 2f, ic, fg, p);
        x += ic + k.cq(DsMetrics.ICON_GAP);
        // the hint on the right, then the label in what is left
        float right = w - k.cq(sz.padX);
        if (hint != null && !hint.isEmpty()) {
            k.text(p, k.body, DsMetrics.T_DESC, DsKit.alpha(fg, .85f));
            p.setLetterSpacing(0);
            float hw = p.measureText(hint);
            c.drawText(hint, right - hw, DsKit.baseline(p, cy), p);
            right -= hw + k.cq(DsMetrics.S2);
        }
        k.text(p, k.title, sz.font, fg);
        p.setLetterSpacing(t.titleSpacing);
        float tw = p.measureText(label), room = right - x;
        if (tw > room && tw > 0) p.setTextSize(p.getTextSize() * room / tw);
        c.drawText(label, x, DsKit.baseline(p, cy), p);
        p.setLetterSpacing(0);
    }
}