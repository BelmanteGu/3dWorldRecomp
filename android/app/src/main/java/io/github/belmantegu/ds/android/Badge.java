package io.github.belmantegu.ds.android;

import io.github.belmantegu.ds.core.*;

import android.graphics.Canvas;
import android.view.View;

/** The small seal beside a row's name ("restart", "experimental"): an icon and a bold capitalised word in a notice colour. Not clickable. */
public final class Badge extends View {
    private final DsKit k;
    private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final String icon, text;
    private final int color;

    public Badge(DsKit kit, String icon, String text, int color) {
        super(kit.context);
        k = kit;
        this.icon = icon;
        this.text = text.toUpperCase(java.util.Locale.ROOT);
        this.color = color;
        setContentDescription(text);
    }

    private void setup() {
        k.text(p, k.body, DsMetrics.T_BADGE, color);
        p.setFakeBoldText(true);
        p.setLetterSpacing(DsMetrics.BADGE_SPACING);
    }

    @Override
    protected void onMeasure(int w, int h) {
        setup();
        float ic = k.cq(DsMetrics.BADGE_ICON);
        setMeasuredDimension(Math.round(ic + k.cq(DsMetrics.BADGE_GAP) + p.measureText(text) + 1), Math.round(k.cq(DsMetrics.BADGE_H)));
    }

    @Override
    protected void onDraw(Canvas c) {
        setup();
        float ic = k.cq(DsMetrics.BADGE_ICON), cy = getHeight() / 2f;
        Icons.draw(c, icon, 0, cy - ic / 2f, ic, color, p);
        setup();
        c.drawText(text, ic + k.cq(DsMetrics.BADGE_GAP), DsKit.baseline(p, cy), p);
    }
}
