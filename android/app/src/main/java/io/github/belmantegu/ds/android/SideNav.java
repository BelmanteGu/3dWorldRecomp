package io.github.belmantegu.ds.android;

import io.github.belmantegu.ds.core.*;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The side column (17 units, no title on top: the tab list takes the room): one item per tab (icon and caption,
 * the selected one on a raised plate with the icon in the accent) and the version at the foot. Items are
 * about 7% of the screen height, so all of them fit without scrolling.
 */
public final class SideNav extends LinearLayout {
    public interface OnSelect {
        void select(int index);
    }

    private final DsKit k;
    private final List<Item> items = new ArrayList<>();
    private final LinearLayout list;
    private final float padV;
    private int selected;
    private boolean focused;
    private OnSelect listener;

    public SideNav(DsKit kit, String version, String[][] entries) {
        super(kit.context);
        k = kit;
        setOrientation(VERTICAL);
        setBackgroundColor(k.t.side);
        padV = DsMetrics.NAV_PAD_Y;
        setPadding(Math.round(k.cq(DsMetrics.NAV_PAD_X)), Math.round(k.cq(padV)), Math.round(k.cq(DsMetrics.NAV_PAD_X)), Math.round(k.cq(padV)));
        list = new LinearLayout(k.context);
        list.setOrientation(VERTICAL);
        LayoutParams lp = new LayoutParams(-1, 0, 1);
        lp.bottomMargin = Math.round(k.cq(DsMetrics.NAV_LIST_GAP));
        addView(list, lp);
        for (int i = 0; i < entries.length; ++i) {
            Item it = new Item(entries[i][0], entries[i][1], i);
            items.add(it);
            list.addView(it, new LayoutParams(-1, Math.round(k.cq(DsMetrics.NAV_ITEM_H))));
        }
        TextView ver = k.label(version, DsMetrics.T_BADGE, k.t.dim);
        ver.setPadding(Math.round(k.cq(DsMetrics.NAV_VER_PAD)), 0, 0, 0);
        addView(ver, new LayoutParams(-1, -2));
    }

    /** The column reaches the window's edges: its padding grows by what lies between the stage and them (the camera hole, the bars, the margin of a wide screen). */
    public void extend(android.graphics.Rect gap) {
        setPadding(getPaddingLeft() + gap.left, getPaddingTop() + gap.top, getPaddingRight(), getPaddingBottom() + gap.bottom);
    }

    public void setOnSelect(OnSelect l) { listener = l; }

    public void setSelected(int i) {
        selected = i;
        for (Item it : items) it.invalidate();
    }

    /** The pad's focus is on the tab list (not on the rows or the Back button). */
    public void setFocusedLook(boolean on) {
        focused = on;
        for (Item it : items) it.invalidate();
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int h = MeasureSpec.getSize(hs);
        if (h > 0 && !items.isEmpty()) {
            // ~7.2% of the screen height each (7 items = half the column), never taller than 4.3 units
            float fixed = getPaddingTop() + getPaddingBottom() + k.cq(DsMetrics.T_BADGE * 1.3f) + 2 * k.cq(DsMetrics.NAV_LIST_GAP);
            int each = Math.round(Math.min(Math.min(k.cq(DsMetrics.NAV_ITEM_H), h * DsMetrics.NAV_ITEM_FRAC), (h - fixed) / items.size()));
            for (Item it : items) {
                LayoutParams lp = (LayoutParams) it.getLayoutParams();
                if (lp.height != each) {
                    lp.height = each;
                    it.setLayoutParams(lp);
                }
            }
        }
        super.onMeasure(ws, hs);
    }

    private final class Item extends DsView {
        private final String icon, label;
        private final int index;

        Item(String icon, String label, int index) {
            super(SideNav.this.k);
            this.icon = icon;
            this.label = label;
            this.index = index;
            setOnClickListener(v -> {
                if (listener != null) listener.select(index);
            });
        }

        @Override
        protected void onDraw(Canvas c) {
            boolean on = index == selected;
            float w = getWidth(), h = getHeight(), inset = k.cq(DsMetrics.NAV_INSET), pad = k.cq(DsMetrics.NAV_PAD);
            p.setStyle(Paint.Style.FILL);
            if (on || isPressed()) {
                p.setColor(on ? k.t.surface2 : k.t.surface);
                k.rect(c, 0, inset, w, h - inset, k.t.rMd, p);
            }
            if (on && focused) {
                p.setStyle(Paint.Style.STROKE);
                float sw = Math.max(2f, k.cq(DsMetrics.STROKE));
                p.setStrokeWidth(sw);
                p.setColor(k.t.focus);
                k.rect(c, sw / 2f, inset + sw / 2f, w - sw / 2f, h - inset - sw / 2f, k.t.rMd, p);
            }
            float ic = k.cq(DsMetrics.ICON_M);
            Icons.draw(c, icon, pad, (h - ic) / 2f, ic, on ? k.t.accent : k.t.dim, p);
            k.text(p, k.body, DsMetrics.T_BODY, on ? k.t.fg : k.t.dim);
            p.setFakeBoldText(true);
            float x = pad + ic + k.cq(DsMetrics.NAV_TEXT_GAP), room = w - x - k.cq(DsMetrics.NAV_RIGHT);
            float tw = p.measureText(label);  // never cut: a long caption (Compatibilidade) shrinks to fit
            if (tw > room) p.setTextSize(p.getTextSize() * room / tw);
            c.drawText(label, x, DsKit.baseline(p, h / 2f), p);
            p.setFakeBoldText(false);
        }
    }
}
