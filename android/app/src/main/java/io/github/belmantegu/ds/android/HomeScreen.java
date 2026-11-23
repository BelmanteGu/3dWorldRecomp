package io.github.belmantegu.ds.android;

import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.belmantegu.ds.core.DsMetrics;
import io.github.belmantegu.ds.core.GameTheme;
import io.github.belmantegu.ds.core.HomeSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * The home screen's layout in the design system (documentation/guides/DESIGN_SYSTEM.md, "Home"), drawn
 * from a HomeSpec on the spacing scale and the button sizes (DsMetrics): the brand line and the pad hints
 * on top, the logo and the subtitle, the menu of L items down the left edge, and to the right of the menu
 * ONE Notice: the one-line status bar (ready or busy, or a short warning with Details), or, when the game
 * cannot be played, the state card (title, one line of explanation, a primary action and Details). Never
 * two at once, and nothing is laid over the menu. The version sits bottom right on a translucent pill.
 * This class builds and updates views; the state and the actions are the game's.
 */
public final class HomeScreen {
    /** What the screen reports back. */
    public interface Listener {
        /** A menu item was tapped (its index in the spec). */
        void onItem(int index);

        /** Details (the notice's button). */
        void onDetails();

        /** The card's primary action (import the .zip, fix the files). */
        void onPrimary();
    }

    /** The status bar stops short of the version pill: the column's width left of it. */
    private static final float BAR_MAX_W = 50f;

    private final HomeUi ui;
    private final DsKit kit;
    private final List<HomeItem> items = new ArrayList<>();
    private final Notice bar, card;
    private final GhostButton barDetails, cardPrimary, cardDetails;
    private final LinearLayout.LayoutParams primaryLp, detailsLp;
    private int selected;
    private final boolean split;
    private final Column column;
    private View extra;

    public HomeScreen(HomeUi ui, FrameLayout root, HomeSpec spec, Listener listener) {
        this.ui = ui;
        this.kit = DsKit.of(ui);
        this.split = spec.menuRight;
        GameTheme t = kit.t;
        android.content.Context a = ui.activity;
        // the page's margin: the left edge for the menu layout, all round for the split one
        final float m = split ? DsMetrics.SPLIT_MARGIN : DsMetrics.MARGIN, corner = split ? DsMetrics.SPLIT_MARGIN : DsMetrics.CORNER;

        // Top: the brand line on the left edge, the pad hints on the right edge.
        TextView kick = ui.text(spec.kicker, DsMetrics.T_BADGE, t.dim, ui.ui);
        kick.setLetterSpacing(.22f);
        root.addView(kick, ui.at(m, DsMetrics.LINE_TOP, -2, -2));
        LinearLayout pads = new LinearLayout(a);
        pads.addView(ui.padHint("A", spec.hintSelect, GameTheme.PAD_A));
        pads.addView(ui.padHint("B", spec.hintBack, GameTheme.PAD_B));
        FrameLayout.LayoutParams padsLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        padsLp.rightMargin = ui.px(corner);
        padsLp.topMargin = ui.px(DsMetrics.LINE_TOP);
        root.addView(pads, padsLp);

        // The logo (the game's art when packed, else the title) and the subtitle.
        Bitmap logo = spec.logoAsset.isEmpty() ? null : ui.loadAsset(spec.logoAsset);
        View logoView;
        float logoH;
        if (logo != null) {
            ImageView img = new ImageView(a);
            img.setImageBitmap(logo);
            img.setScaleType(ImageView.ScaleType.FIT_XY);  // the column measures it to the picture's own proportions
            logoView = img;
            logoH = DsMetrics.LOGO_W * logo.getHeight() / logo.getWidth();
        } else {
            TextView title = kit.titleLabel(spec.title, DsMetrics.T_TITLE, t.fg);
            title.setLineSpacing(0, .95f);
            logoView = title;
            logoH = DsMetrics.LOGO_W / 2f;
        }
        TextView subtitle = ui.text(spec.subtitle, DsMetrics.T_BADGE, t.focus, ui.ui);
        subtitle.setLetterSpacing(.1f);

        // The menu: L items of one width and height, S1 apart.
        LinearLayout menu = new LinearLayout(a);
        menu.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < spec.items.size(); ++i) {
            HomeSpec.Item it = spec.items.get(i);
            HomeItem item = new HomeItem(kit, it.icon, it.label, it.hint);
            if (split) item.plateHeight(DsMetrics.SPLIT_ITEM_H);
            final int index = i;
            item.setOnClickListener(v -> { select(index); listener.onItem(index); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) lp.topMargin = ui.px(DsMetrics.S1);
            menu.addView(item, lp);
            items.add(item);
        }

        // The status bar and the state card (game files missing or damaged).
        bar = new Notice(kit, ui, Notice.Variant.OK).compact().maxWidthUnits(split ? DsMetrics.SPLIT_COL_W : BAR_MAX_W);
        barDetails = new GhostButton(kit, DsMetrics.S, false).raised();
        barDetails.set(null, spec.details);
        barDetails.setOnClickListener(v -> listener.onDetails());
        card = new Notice(kit, ui, Notice.Variant.WARN).stackActions().maxWidthUnits(DsMetrics.CARD_W_STATE);
        cardPrimary = new GhostButton(kit, DsMetrics.M, true);
        cardPrimary.setOnClickListener(v -> listener.onPrimary());
        cardDetails = new GhostButton(kit, DsMetrics.M, false).raised();
        cardDetails.set(null, spec.details);
        cardDetails.setOnClickListener(v -> listener.onDetails());
        primaryLp = new LinearLayout.LayoutParams(-2, -2);
        detailsLp = new LinearLayout.LayoutParams(-2, -2);
        card.setVisibility(View.GONE);

        if (split) {
            // Left: one column, top to bottom: the logo (takes what is left), the subtitle, the extra (the channels), the state.
            float colW = Math.min(DsMetrics.SPLIT_COL_W, DsMetrics.STAGE_W - 2 * DsMetrics.SPLIT_MARGIN - DsMetrics.SPLIT_MENU_W - DsMetrics.SPLIT_COL_GAP);
            column = new Column(a, logoView, subtitle, bar, card);
            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ui.px(colW), -1, Gravity.TOP | Gravity.START);
            cl.leftMargin = ui.px(m);
            cl.topMargin = ui.px(DsMetrics.SPLIT_COL_TOP);
            cl.bottomMargin = ui.px(DsMetrics.SPLIT_BOTTOM);
            root.addView(column, cl);
            // Right: the menu, centred in the room between the pad hints and the version. Every item always fits: when
            // the room is short the items share it out (shorter plates) instead of running past the bottom.
            FitMenu menuBox = new FitMenu(a, items, ui.px(DsMetrics.S1), kit.touch(DsMetrics.SPLIT_ITEM_H));
            menuBox.addView(menu, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL));
            FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(ui.px(DsMetrics.SPLIT_MENU_W), -1, Gravity.TOP | Gravity.END);
            ml.rightMargin = ui.px(m);
            ml.topMargin = ui.px(DsMetrics.SPLIT_MENU_TOP);
            ml.bottomMargin = ui.px(DsMetrics.SPLIT_MENU_BOTTOM);
            root.addView(menuBox, ml);
        } else {
            column = null;
            float y = DsMetrics.LOGO_TOP;
            if (logo != null) {
                root.addView(logoView, ui.at(DsMetrics.COL_CONTENT - DsMetrics.S1 / 2f, y, DsMetrics.LOGO_W, -2));
                ((ImageView) logoView).setAdjustViewBounds(true);
                ((ImageView) logoView).setScaleType(ImageView.ScaleType.FIT_CENTER);
            } else {
                root.addView(logoView, ui.at(DsMetrics.COL_CONTENT, y, -2, -2));
            }
            float subY = y + logoH + DsMetrics.S1 / 2f;
            root.addView(subtitle, ui.at(DsMetrics.COL_CONTENT, subY, -2, -2));
            float menuTop = subY + DsMetrics.SUBTITLE_H + DsMetrics.S2;
            root.addView(menu, ui.at(DsMetrics.COL_CONTENT, menuTop, DsMetrics.MENU_W, -2));
            // the status bar: bottom of the right-hand column, level with the last item
            FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
            barLp.leftMargin = ui.px(DsMetrics.COL_STATE);
            barLp.bottomMargin = ui.px(DsMetrics.CORNER);
            root.addView(bar, barLp);
            root.addView(card, ui.at(DsMetrics.COL_STATE, menuTop, -2, -2));
        }

        TextView ver = ui.text(spec.version, DsMetrics.T_DESC, t.fg, ui.ui);
        ver.setLetterSpacing(.04f);
        ver.setPadding(ui.px(DsMetrics.S3), ui.px(DsMetrics.S2), ui.px(DsMetrics.S3), ui.px(DsMetrics.S2));
        ver.setBackground(pill(GameTheme.SHADE, t.radius(DsMetrics.S)));
        FrameLayout.LayoutParams verLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        verLp.rightMargin = ui.px(corner);
        verLp.bottomMargin = ui.px(split ? DsMetrics.SPLIT_BOTTOM : DsMetrics.CORNER);
        root.addView(ver, verLp);
        select(0);
    }

    /**
     * Something the game puts under the subtitle of the split layout (the channels), hidden while the state card shows.
     * False (and nothing done) in the menu layout, where the game places it itself.
     */
    public boolean setExtra(View extra) {
        if (column == null) return false;
        column.setExtra(extra);
        this.extra = extra;
        return true;
    }

    /**
     * The split layout's left column, measured by hand: the logo takes the height the others leave (kept in
     * proportion, left aligned, centred in its room), then come the subtitle, the extra and the state, S2 apart,
     * the state ending on the bottom edge. When the logo would be tiny the extra gives way; nothing is cut.
     */
    private final class Column extends android.view.ViewGroup {
        private final View logo, subtitle, bar, card;
        private View extra;
        private final int gapPx = ui.px(DsMetrics.SPLIT_GAP), minLogo = ui.px(DsMetrics.SPLIT_LOGO_MIN);
        private boolean showExtra;

        Column(android.content.Context c, View logo, View subtitle, View bar, View card) {
            super(c);
            this.logo = logo;
            this.subtitle = subtitle;
            this.bar = bar;
            this.card = card;
            addView(logo);
            addView(subtitle);
            addView(bar);
            addView(card);
        }

        void setExtra(View v) {
            extra = v;
            addView(v);
        }

        private View state() { return card.getVisibility() == VISIBLE ? card : bar; }

        private int h(View v) { return v == null || v.getVisibility() == GONE ? 0 : v.getMeasuredHeight(); }

        @Override
        protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws), hAll = MeasureSpec.getSize(hs);
            int at = MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), free = MeasureSpec.makeMeasureSpec(hAll, MeasureSpec.AT_MOST);
            subtitle.measure(at, free);
            state().measure(at, free);
            View x = extra != null && extra.getVisibility() != GONE ? extra : null;
            if (x != null) x.measure(at, free);
            int rest = h(subtitle) + h(state()) + gapPx * 2 + (x != null ? h(x) + gapPx : 0);
            showExtra = x != null;
            if (x != null && hAll - rest < minLogo) {  // too little room for the logo: the extra gives way
                showExtra = false;
                rest -= h(x) + gapPx;
            }
            int room = Math.max(0, hAll - rest);
            if (logo instanceof ImageView && ((ImageView) logo).getDrawable() != null) {
                android.graphics.drawable.Drawable d = ((ImageView) logo).getDrawable();
                float fit = Math.min((float) w / d.getIntrinsicWidth(), (float) room / d.getIntrinsicHeight());
                logo.measure(MeasureSpec.makeMeasureSpec(Math.max(1, Math.round(d.getIntrinsicWidth() * fit)), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(Math.max(1, Math.round(d.getIntrinsicHeight() * fit)), MeasureSpec.EXACTLY));
            } else {
                logo.measure(at, MeasureSpec.makeMeasureSpec(room, MeasureSpec.AT_MOST));
            }
            roomPx = room;
            setMeasuredDimension(w, hAll);
        }

        private int roomPx;

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            logo.layout(0, (roomPx - logo.getMeasuredHeight()) / 2, logo.getMeasuredWidth(), (roomPx - logo.getMeasuredHeight()) / 2 + logo.getMeasuredHeight());
            int y = roomPx + gapPx;
            subtitle.layout(0, y, subtitle.getMeasuredWidth(), y + subtitle.getMeasuredHeight());
            y += subtitle.getMeasuredHeight() + gapPx;
            if (extra != null) {
                if (showExtra) {
                    extra.layout(0, y, extra.getMeasuredWidth(), y + extra.getMeasuredHeight());
                    y += extra.getMeasuredHeight() + gapPx;
                } else {
                    extra.layout(0, 0, 0, 0);
                }
            }
            View s = state();
            View other = s == card ? bar : card;
            s.layout(0, y, s.getMeasuredWidth(), y + s.getMeasuredHeight());
            other.layout(0, 0, 0, 0);
        }
    }

    private GradientDrawable pill(int color, float radiusUnits) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(kit.cq(radiusUnits));
        return d;
    }

    public int selected() { return selected; }

    public void select(int i) {
        selected = (i + items.size()) % items.size();
        for (int j = 0; j < items.size(); ++j) items.get(j).setOn(j == selected);
    }

    public GameTheme theme() { return kit.t; }

    /** Item `index` locked (lock icon, muted, with the reason on it) or playable (null). */
    public void setLocked(int index, String reason) {
        items.get(index).setLocked(reason != null, reason);
    }

    /**
     * The one-line bar: `variant` sets the icon and colour; a `warning` (null for none) becomes the title
     * with `state` as its text and the Details button. Hides the card.
     */
    public void setBar(Notice.Variant variant, String state, String meta, String warning) {
        boolean warn = warning != null && !warning.isEmpty();
        bar.setVariant(warn ? Notice.Variant.WARN : variant);
        if (warn) bar.set(warning, state);
        else bar.set(state, meta);
        bar.actions(warn ? new View[] {barDetails} : new View[0]);
        card.setVisibility(View.GONE);
        bar.setVisibility(View.VISIBLE);
        if (extra != null) extra.setVisibility(View.VISIBLE);
    }

    /** The card of an unplayable state; hides the bar. */
    public void setCard(String icon, Notice.Variant variant, String title, String text, String primaryIcon, String primaryCaption) {
        card.icon(icon).setVariant(variant);
        card.set(title, text);
        cardPrimary.set(primaryIcon, primaryCaption);
        // the two actions are one width (the wider one)
        int w = (int) Math.max(cardPrimary.contentWidth(), cardDetails.contentWidth());
        primaryLp.width = w;
        detailsLp.width = w;
        cardPrimary.setLayoutParams(primaryLp);
        cardDetails.setLayoutParams(detailsLp);
        card.actions(cardPrimary, cardDetails);
        bar.setVisibility(View.GONE);
        card.setVisibility(View.VISIBLE);
        if (extra != null) extra.setVisibility(View.GONE);  // the card needs the room
    }

    /** An import's progress: the dim text of the bar and the line under it (negative hides the line). */
    public void setProgress(String meta, float fraction) {
        if (meta != null) bar.set(bar.titleText(), meta);
        bar.setProgress(fraction);
    }

    /** The split menu's box: gives each item the same height, at most `itemPx`, so all of them fit in its height. */
    private static final class FitMenu extends FrameLayout {
        private final List<HomeItem> items;
        private final int gapPx, itemPx;

        FitMenu(android.content.Context c, List<HomeItem> items, int gapPx, int itemPx) {
            super(c);
            this.items = items;
            this.gapPx = gapPx;
            this.itemPx = itemPx;
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int n = Math.max(1, items.size()), room = MeasureSpec.getSize(hSpec);
            int each = Math.max(1, Math.min(itemPx, (room - gapPx * (n - 1)) / n));
            for (HomeItem it : items) {
                android.view.ViewGroup.LayoutParams lp = it.getLayoutParams();
                if (lp != null && lp.height != each) { lp.height = each; it.setLayoutParams(lp); }
            }
            super.onMeasure(wSpec, hSpec);
        }
    }
}
