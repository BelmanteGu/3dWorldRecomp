package io.github.belmantegu.ds.android;

import io.github.belmantegu.ds.core.*;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The design system's one notice component (documentation/guides/DESIGN_SYSTEM.md, "Notices"): the state
 * card and the status bar of the home screen, the restart banner, the warnings of a tab and the app's
 * toasts are all this. A Lucide icon in the variant's semantic colour (info, ok, warn, error; busy is info
 * with the refresh icon), a title of one line, optional text of up to two lines, and actions on the right
 * (GhostButton of size M or S). The plate is the theme's surface with its medium radius, the padding and
 * the minimum height are DsMetrics', and the width never passes NOTICE_MAX_W. compact() puts the text
 * beside the title on one line (the one-line status bar). An import's progress line can sit under it.
 */
public final class Notice extends LinearLayout {
    public enum Variant { INFO, OK, WARN, ERROR, BUSY }

    private final DsKit k;
    private final IconView icon;
    private final TextView title, text;
    private final LinearLayout column, actions;
    private final HomeUi.ProgressLine progress;
    private Variant variant;
    private float maxUnits = DsMetrics.NOTICE_MAX_W;
    private String iconOverride;

    public Notice(DsKit kit, HomeUi ui, Variant v) {
        super(kit.context);
        k = kit;
        setOrientation(VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(DsKit.alpha(k.t.surface, .94f));
        bg.setCornerRadius(k.cq(k.t.radius(DsMetrics.M)));
        setBackground(bg);
        setPadding(Math.round(k.cq(DsMetrics.NOTICE_PAD_X)), Math.round(k.cq(DsMetrics.NOTICE_PAD_Y)),
                Math.round(k.cq(DsMetrics.NOTICE_PAD_X)), Math.round(k.cq(DsMetrics.NOTICE_PAD_Y)));
        setMinimumHeight(Math.round(k.cq(DsMetrics.NOTICE_MIN_H)));

        LinearLayout row = new LinearLayout(kit.context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        icon = new IconView(kit.context, "info", k.t.fg);
        row.addView(icon, new LayoutParams(Math.round(k.cq(DsMetrics.NOTICE_ICON)), Math.round(k.cq(DsMetrics.NOTICE_ICON))));
        column = new LinearLayout(kit.context);
        column.setOrientation(VERTICAL);
        title = k.label("", DsMetrics.T_BODY, k.t.fg);
        title.getPaint().setFakeBoldText(true);
        text = k.label("", DsMetrics.T_DESC, k.t.dim);
        text.setLineSpacing(0, DsMetrics.T_LINE);
        column.addView(title, new LayoutParams(-2, -2));
        LayoutParams tp = new LayoutParams(-2, -2);
        tp.topMargin = Math.round(k.cq(DsMetrics.S1 / 2f));
        column.addView(text, tp);
        LayoutParams cp = new LayoutParams(0, -2, 1f);
        cp.leftMargin = Math.round(k.cq(DsMetrics.ICON_GAP));
        row.addView(column, cp);
        actions = new LinearLayout(kit.context);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams ap = new LayoutParams(-2, -2);
        ap.leftMargin = Math.round(k.cq(DsMetrics.S3));
        row.addView(actions, ap);
        addView(row, new LayoutParams(-1, -2));
        progress = ui.new ProgressLine();
        LayoutParams pp = new LayoutParams(-1, Math.max(2, Math.round(k.cq(DsMetrics.S1 * .8f))));
        pp.topMargin = Math.round(k.cq(DsMetrics.S2));
        addView(progress, pp);
        progress.set(-1);
        setVariant(v);
    }

    public Notice setVariant(Variant v) {
        variant = v;
        int color = v == Variant.OK ? k.t.ok : v == Variant.WARN ? k.t.warn : v == Variant.ERROR ? k.t.danger : k.t.focus;
        String name = iconOverride != null ? iconOverride
                : v == Variant.OK ? "circle-check" : v == Variant.WARN ? "triangle-alert" : v == Variant.ERROR ? "circle-x"
                : v == Variant.BUSY ? "refresh-cw" : "info";
        icon.set(name, color);
        title.setTextColor(v == Variant.OK || v == Variant.BUSY || v == Variant.INFO ? k.t.fg : color);
        return this;
    }

    public Variant variant() { return variant; }

    public String titleText() { return title.getText().toString(); }

    /** A fixed icon for this notice (the restart banner's refresh arrows), whatever the variant. */
    public Notice icon(String name) {
        iconOverride = name;
        setVariant(variant);
        return this;
    }

    /** The title (one line) and the text (about two lines, may be empty; never cut). */
    public Notice set(String titleText, String body) {
        title.setText(titleText);
        title.setVisibility(titleText == null || titleText.isEmpty() ? GONE : VISIBLE);
        text.setText(body);
        text.setVisibility(body == null || body.isEmpty() ? GONE : VISIBLE);
        return this;
    }

    /** One line: the text beside the title instead of under it. */
    public Notice compact() {
        column.setOrientation(HORIZONTAL);
        column.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams tp = (LayoutParams) text.getLayoutParams();
        tp.topMargin = 0;
        tp.leftMargin = Math.round(k.cq(DsMetrics.S3));
        return this;
    }

    /** Puts the actions under the text (aligned with it) instead of on the right: a card whose title must stay on one line. */
    public Notice stackActions() {
        android.view.ViewGroup row = (android.view.ViewGroup) actions.getParent();
        row.removeView(actions);
        LayoutParams ap = new LayoutParams(-2, -2);
        ap.topMargin = Math.round(k.cq(DsMetrics.S3));
        ap.leftMargin = Math.round(k.cq(DsMetrics.NOTICE_ICON + DsMetrics.ICON_GAP));
        addView(actions, 1, ap);  // between the row and the progress line
        return this;
    }

    public Notice maxWidthUnits(float units) {
        maxUnits = units;
        return this;
    }

    /** Replaces the actions on the right. Buttons of one group should be given one width by the caller. */
    public Notice actions(View... buttons) {
        actions.removeAllViews();
        actions.setVisibility(buttons.length == 0 ? GONE : VISIBLE);
        for (int i = 0; i < buttons.length; ++i) {
            if (buttons[i].getParent() instanceof LinearLayout) ((LinearLayout) buttons[i].getParent()).removeView(buttons[i]);
            LayoutParams lp = buttons[i].getLayoutParams() instanceof LayoutParams ? new LayoutParams(buttons[i].getLayoutParams())
                    : new LayoutParams(-2, -2);
            if (i > 0) lp.leftMargin = Math.round(k.cq(DsMetrics.S2));
            actions.addView(buttons[i], lp);
        }
        return this;
    }

    /** 0..1, or negative to hide the line. */
    public void setProgress(float fraction) {
        progress.set(fraction);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int cap = Math.round(k.cq(maxUnits));
        int mode = MeasureSpec.getMode(wSpec), size = MeasureSpec.getSize(wSpec);
        if (mode == MeasureSpec.UNSPECIFIED || size > cap) wSpec = MeasureSpec.makeMeasureSpec(cap, mode == MeasureSpec.EXACTLY ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST);
        super.onMeasure(wSpec, hSpec);
    }
}