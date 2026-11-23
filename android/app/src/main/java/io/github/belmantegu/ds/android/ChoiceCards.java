package io.github.belmantegu.ds.android;

import io.github.belmantegu.ds.core.*;

import android.graphics.Canvas;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Choice cards (the Profile row): two to four cards with an icon, a name and a short line; the selected
 * one is raised (surface2), its icon takes the accent and a round check sits at its corner. At most 40
 * units wide. No card is selected when `selected` is -1 (a custom preset).
 */
public final class ChoiceCards extends DsView {
    public static final class Card {
        public final String icon, title, sub;

        Card(String icon, String title, String sub) {
            this.icon = icon;
            this.title = title;
            this.sub = sub;
        }
    }

    private final List<Card> cards = new ArrayList<>();
    private int selected = -1;
    private Segmented.OnPick listener;

    public ChoiceCards(DsKit kit) {
        super(kit);
    }

    public void setOnPick(Segmented.OnPick l) { listener = l; }

    public void set(List<Card> items, int sel) {
        if (cards.size() != items.size()) requestLayout();
        cards.clear();
        cards.addAll(items);
        selected = sel;
        invalidate();
    }

    private float cardH() {
        return k.cq(DsMetrics.CARD_TOP + DsMetrics.CARD_ICON + DsMetrics.CARD_ICON_GAP + DsMetrics.T_DESC * DsMetrics.CARD_LINE + DsMetrics.CARD_ICON_GAP
                + DsMetrics.T_BADGE * DsMetrics.CARD_LINE + DsMetrics.CARD_TOP);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        float w = k.cq(DsMetrics.CARD_W);
        int avail = MeasureSpec.getSize(wSpec);
        if (MeasureSpec.getMode(wSpec) != MeasureSpec.UNSPECIFIED && avail > 0) w = Math.min(w, avail);
        setMeasuredDimension(Math.round(w), Math.round(cardH()));
    }

    private float cardW() {
        int n = Math.max(1, cards.size());
        return (getWidth() - (n - 1) * k.cq(DsMetrics.CARD_GAP)) / n;
    }

    @Override
    protected void onDraw(Canvas c) {
        float cw = cardW(), h = getHeight(), x = 0, pad = k.cq(DsMetrics.CARD_PAD);
        for (int i = 0; i < cards.size(); ++i) {
            Card cd = cards.get(i);
            boolean on = i == selected;
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(shade(on ? k.t.surface2 : k.t.surface));
            k.rect(c, x, 0, x + cw, h, k.t.rMd, p);
            float ic = k.cq(DsMetrics.CARD_ICON), y = k.cq(DsMetrics.CARD_TOP);
            Icons.draw(c, cd.icon, x + pad, y, ic, shade(on ? k.t.accent : k.t.dim), p);
            y += ic + k.cq(DsMetrics.CARD_ICON_GAP);
            k.text(p, k.body, DsMetrics.T_DESC, shade(k.t.fg));
            p.setFakeBoldText(true);
            c.drawText(fit(cd.title, cw - 2 * pad), x + pad, y - p.ascent(), p);
            p.setFakeBoldText(false);
            y += k.cq(DsMetrics.T_DESC * DsMetrics.CARD_LINE + DsMetrics.CARD_ICON_GAP);
            k.text(p, k.body, DsMetrics.T_BADGE, shade(k.t.dim));
            c.drawText(fit(cd.sub, cw - 2 * pad), x + pad, y - p.ascent(), p);
            if (on) {
                float d = k.cq(DsMetrics.CARD_CHECK), cx = x + cw - k.cq(DsMetrics.CARD_CHECK_INSET) - d / 2f, cy = k.cq(DsMetrics.CARD_CHECK_INSET) + d / 2f;
                p.setStyle(android.graphics.Paint.Style.FILL);
                p.setColor(shade(k.t.accent));
                c.drawCircle(cx, cy, d / 2f, p);
                float s = d * .7f;
                Icons.draw(c, "check", cx - s / 2f, cy - s / 2f, s, shade(k.t.accentFg), 3.2f, p);
            }
            x += cw + k.cq(DsMetrics.CARD_GAP);
        }
    }

    private String fit(String s, float room) {
        while (s.length() > 2 && p.measureText(s) > room) s = s.substring(0, s.length() - 2) + "…";
        return s;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean r = super.onTouchEvent(e);
        if (e.getActionMasked() == MotionEvent.ACTION_UP && listener != null && !cards.isEmpty()) {
            int i = (int) (e.getX() / (cardW() + k.cq(DsMetrics.CARD_GAP)));
            listener.pick(Math.max(0, Math.min(cards.size() - 1, i)));
        }
        return r;
    }
}
