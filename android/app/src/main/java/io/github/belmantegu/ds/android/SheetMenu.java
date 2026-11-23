package io.github.belmantegu.ds.android;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.belmantegu.ds.core.DsMetrics;
import io.github.belmantegu.ds.core.GameTheme;
import io.github.belmantegu.ds.core.SheetSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * The in-game sheet (documentation/guides/DESIGN_SYSTEM.md, "In-game menu"): a translucent side sheet on
 * the left, about a third of the width, over the game dimmed by a scrim. It has the title, the items with
 * their icons (L size, scrolling when the sheet is short), two small lines at the bottom (the frame limit
 * and the shadows, from the game) and a pill on the right. The game declares it with a SheetSpec and keeps
 * the actions; B resumes (the game's "continue" key). The game's own surface cannot be blurred from a
 * sibling view, so the dim is a scrim and a darker sheet.
 */
public final class SheetMenu extends FrameLayout {
    public interface Host {
        /** An entry was activated: its key in the spec. */
        void onPick(String key);
    }

    private final HomeUi ui;
    private final DsKit kit;
    private final Host host;
    private final String backKey;
    private final LinearLayout list;
    private final android.widget.ScrollView scroll;
    private final TextView footLeft, footRight;
    private final List<HomeItem> items = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();
    private int selected;

    /** `backKey` is the item B and BACK trigger. */
    public SheetMenu(HomeUi ui, String title, String subtitle, String pill, String backKey, Host host) {
        super(ui.activity);
        this.ui = ui;
        this.host = host;
        this.backKey = backKey;
        this.kit = DsKit.of(ui);
        GameTheme t = kit.t;
        setVisibility(GONE);
        setClickable(true);
        setBackgroundColor(GameTheme.SCRIM);  // the game dimmed

        LinearLayout sheet = new LinearLayout(ui.activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackgroundColor(DsKit.alpha(t.side, .90f));
        sheet.setPadding(ui.gap.left + ui.px(DsMetrics.COL_CONTENT), ui.gap.top + ui.px(DsMetrics.MARGIN), ui.px(DsMetrics.S4), ui.gap.bottom + ui.px(DsMetrics.MARGIN));
        sheet.setClickable(true);  // taps on the sheet are not "outside"
        addView(sheet, new FrameLayout.LayoutParams(ui.gap.left + ui.px(DsMetrics.SHEET_W), -1, Gravity.START));

        sheet.addView(kit.titleLabel(title, DsMetrics.T_TITLE, t.fg));
        TextView sub = kit.label(subtitle, DsMetrics.T_DESC, t.dim);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.topMargin = ui.px(DsMetrics.S1);
        sheet.addView(sub, sl);

        // the items scroll when the sheet is short (five items of size L do not fit a 16:9 phone)
        scroll = new android.widget.ScrollView(ui.activity);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(ui.activity);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, 0, 1f);
        ll.topMargin = ui.px(DsMetrics.S3);
        sheet.addView(scroll, ll);

        LinearLayout foot = new LinearLayout(ui.activity);
        footLeft = kit.label("", DsMetrics.T_DESC, t.dim);
        footRight = kit.label("", DsMetrics.T_DESC, t.dim);
        footRight.setPadding(ui.px(DsMetrics.S3), 0, 0, 0);
        foot.addView(footLeft);
        foot.addView(footRight);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-2, -2);
        fl.topMargin = ui.px(DsMetrics.S2);
        sheet.addView(foot, fl);

        // The state pill, bottom right.
        LinearLayout pillView = new LinearLayout(ui.activity);
        pillView.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(DsKit.alpha(t.surface, .88f));
        bg.setCornerRadius(ui.px(DsMetrics.PILL));
        pillView.setBackground(bg);
        pillView.setPadding(ui.px(DsMetrics.S3), ui.px(DsMetrics.S2), ui.px(DsMetrics.S3), ui.px(DsMetrics.S2));
        View dot = new View(ui.activity);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(t.accent);
        dot.setBackground(d);
        pillView.addView(dot, new LinearLayout.LayoutParams(ui.px(DsMetrics.PAUSE_DOT), ui.px(DsMetrics.PAUSE_DOT)));
        TextView state = kit.label(pill, DsMetrics.T_DESC, t.fg);
        state.getPaint().setFakeBoldText(true);
        state.setPadding(ui.px(DsMetrics.PAUSE_PILL_GAP), 0, 0, 0);
        pillView.addView(state);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        pl.rightMargin = ui.gap.right + ui.px(DsMetrics.CORNER);
        pl.bottomMargin = ui.gap.bottom + ui.px(DsMetrics.CORNER);
        addView(pillView, pl);
    }

    /** The two small lines at the bottom (the frame limit and the shadows, from the game). */
    public void setFooter(String left, String right) {
        footLeft.setText(left);
        footRight.setText(right);
    }

    /** Rebuilds the entries (the restart entry comes and goes); keeps the highlight on the same key when it can. */
    public void setItems(List<SheetSpec.Item> entries) {
        String was = selected < keys.size() ? keys.get(selected) : "";
        list.removeAllViews();
        items.clear();
        keys.clear();
        for (SheetSpec.Item e : entries) {
            HomeItem item = new HomeItem(kit, e.icon, e.label, "");
            final int index = keys.size();
            item.setOnClickListener(v -> { select(index); activate(); });
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
            if (index > 0) ilp.topMargin = ui.px(DsMetrics.S1);
            list.addView(item, ilp);
            items.add(item);
            keys.add(e.key);
        }
        select(Math.max(0, keys.indexOf(was)));
    }

    public boolean isOpen() { return getVisibility() == VISIBLE; }

    public void show() {
        select(0);
        setVisibility(VISIBLE);
    }

    public void hide() { setVisibility(GONE); }

    /** The highlighted entry's key. */
    public String selectedKey() { return selected < keys.size() ? keys.get(selected) : ""; }

    public void select(int i) {
        if (items.isEmpty()) return;
        selected = (i + items.size()) % items.size();
        for (int j = 0; j < items.size(); ++j) items.get(j).setOn(j == selected);
        final HomeItem now = items.get(selected);
        scroll.post(() -> scroll.requestChildRectangleOnScreen(list,
                new android.graphics.Rect(0, now.getTop(), now.getWidth(), now.getBottom()), false));
    }

    private void activate() {
        if (selected < keys.size()) host.onPick(keys.get(selected));
    }

    /** D-pad / A / B; B resumes. True when handled (always, while open). */
    public boolean onKey(int keyCode, boolean repeat) {
        if (!isOpen()) return false;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: select(selected - 1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: select(selected + 1); break;
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_SPACE:
                if (!repeat) activate();
                break;
            case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_ESCAPE: case KeyEvent.KEYCODE_BACK:
                if (!repeat) host.onPick(backKey);
                break;
            default: break;
        }
        return true;
    }
}