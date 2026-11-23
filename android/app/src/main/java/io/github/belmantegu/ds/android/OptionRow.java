package io.github.belmantegu.ds.android;

import io.github.belmantegu.ds.core.*;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * One row of an options list: name (with its badges) and description in a 31-unit column on the left,
 * the control in the column that follows, a hairline above (no boxes, no outlines). The focused row (pad,
 * keys) gets a raised plate and the focus colour's rim. min height 5.2 units; the whole row is a tap target
 * that focuses it.
 */
public final class OptionRow extends LinearLayout {
    private final DsKit k;
    private final TextView title, desc;
    private final boolean divider;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LinearLayout labels;
    private final View holder;
    private boolean focused;

    public OptionRow(DsKit kit, String name, String description, List<View> badges, View control, boolean divider) {
        super(kit.context);
        k = kit;
        this.divider = divider;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setWillNotDraw(false);
        setMinimumHeight(Math.round(k.cq(DsMetrics.ROW_MIN_H)));
        int hp = Math.round(k.cq(DsMetrics.ROW_PAD_X)), vp = Math.round(k.cq(DsMetrics.ROW_PAD_Y));
        setPadding(hp, vp, hp, vp);
        setClickable(true);
        setFocusable(false);
        setDefaultFocusHighlightEnabled(false);

        LinearLayout labels = new LinearLayout(k.context);
        labels.setOrientation(VERTICAL);
        LinearLayout line = new LinearLayout(k.context);
        line.setOrientation(HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        title = k.label(name, DsMetrics.T_BODY, k.t.fg);
        title.setPaintFlags(title.getPaintFlags() | Paint.FAKE_BOLD_TEXT_FLAG);
        line.addView(title, new LayoutParams(-2, -2));
        // the badges sit beside the name when they fit the 31-unit column, else on a line of their own (never cut)
        float need = title.getPaint().measureText(name) * 1.03f;
        for (View b : badges) {
            b.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
            need += b.getMeasuredWidth() + k.cq(DsMetrics.ROW_BADGE_GAP);
        }
        boolean stacked = need > k.cq(DsMetrics.ROW_STACK_AT);
        LinearLayout badgeLine = stacked ? new LinearLayout(k.context) : line;
        for (View b : badges) {
            LayoutParams bp = new LayoutParams(-2, -2);
            if (!stacked || b != badges.get(0)) bp.leftMargin = Math.round(k.cq(DsMetrics.ROW_BADGE_GAP));
            badgeLine.addView(b, bp);
        }
        labels.addView(line, new LayoutParams(-2, -2));
        if (stacked) {
            LayoutParams sp = new LayoutParams(-2, -2);
            sp.topMargin = Math.round(k.cq(DsMetrics.ROW_BADGE_LINE_GAP));
            labels.addView(badgeLine, sp);
        }
        desc = k.label(description, DsMetrics.T_DESC, k.t.dim);
        desc.setLineSpacing(0, DsMetrics.T_LINE);
        LayoutParams dp = new LayoutParams(-2, -2);
        dp.topMargin = Math.round(k.cq(DsMetrics.ROW_DESC_GAP));
        labels.addView(desc, dp);
        desc.setVisibility(description.isEmpty() ? GONE : VISIBLE);
        this.labels = labels;
        addView(labels, new LayoutParams(Math.round(k.cq(DsMetrics.ROW_LABEL_W)), -2));

        FrameLayout holder = new FrameLayout(k.context);
        this.holder = holder;
        holder.addView(control, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
        LayoutParams hl = new LayoutParams(0, -2, 1);
        hl.leftMargin = Math.round(k.cq(DsMetrics.ROW_CONTROL_GAP));
        addView(holder, hl);
    }

    /** The label column is 31 units, and gives way (down to 14) when the control needs more than the rest of the row. */
    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int total = MeasureSpec.getSize(wSpec) - getPaddingLeft() - getPaddingRight() - Math.round(k.cq(DsMetrics.ROW_CONTROL_GAP));
        holder.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        int want = Math.round(k.cq(DsMetrics.ROW_LABEL_W)), room = total - holder.getMeasuredWidth();
        int w = Math.max(Math.round(k.cq(DsMetrics.ROW_LABEL_MIN)), Math.min(want, room));
        LayoutParams lp = (LayoutParams) labels.getLayoutParams();
        if (lp.width != w) lp.width = w;
        super.onMeasure(wSpec, hSpec);
    }

    public void setFocusedRow(boolean on) {
        if (focused != on) {
            focused = on;
            invalidate();
        }
    }

    public void setTitleColor(int color) {
        title.setTextColor(color);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (focused) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(k.t.surface);
            k.rect(c, 0, 0, w, h, k.t.rMd, p);
            p.setStyle(Paint.Style.STROKE);
            float sw = Math.max(2f, k.cq(DsMetrics.STROKE));
            p.setStrokeWidth(sw);
            p.setColor(k.t.focus);
            k.rect(c, sw / 2f, sw / 2f, w - sw / 2f, h - sw / 2f, k.t.rMd, p);
        } else if (divider) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(k.t.line);
            c.drawRect(getPaddingLeft(), 0, w - getPaddingRight(), Math.max(1f, k.cq(DsMetrics.HAIRLINE)), p);
        }
    }
}
