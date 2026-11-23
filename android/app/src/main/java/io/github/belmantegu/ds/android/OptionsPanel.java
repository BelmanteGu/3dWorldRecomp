package io.github.belmantegu.ds.android;

import io.github.belmantegu.ds.core.*;

import android.content.Context;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The Options screen, one class for the home screen and the in-game menu, in the design system
 * (documentation/guides/DESIGN_SYSTEM.md): full screen and opaque, a side column with the
 * tabs (icon and caption) and the version, a minimal header (Back as an icon, title, subtitle, the restart
 * notice) and rows separated by a hairline with the control in a fixed column. The tabs, rows, values and
 * navigation are OptionsModel (plain Java, tested); this builds the views from the Ds components and routes
 * input. Pad: UP from the first tab focuses Back; B steps back from the rows to the tabs, then closes; LB / RB
 * (or Tab, Page Up / Down) change the tab.
 */
public final class OptionsPanel extends FrameLayout {
    /** What the host does for the rows that need its screen or an activity result. */
    public interface Host {
        /** An action row the panel does not handle itself: "editor", "folder", "backup", "restore", "driver_import", "driver_delete", "diag". */
        void onAction(String key);

        /** A value changed (already saved), or "reset:<tab>" after Restore defaults: apply what applies live. */
        void onChanged(String key);

        /** The panel closed (B on the tabs, Back). */
        void onClosed();

        /** A short notice in the app's look. */
        void say(String message);

        /** True when any restart-only setting differs from the running game's (the banner). */
        boolean restartNeeded();
    }

    private final HomeUi ui;
    private final DsKit kit;
    private final Host host;
    private final OptionsEngine model;
    private final String versionText;
    private final List<Entry> entries = new ArrayList<>();
    private final LinearLayout rowList;
    private final ScrollView rowScroll;
    private final SideNav nav;
    private final IconButton back;
    private final TextView titleView, subView;
    private final Notice banner;
    private final OptionsControls.Sink sink;
    private int shownTab = -1;
    private int lastStick;
    private boolean backFocused;

    /** A built row: the layout, the control and the model row. */
    private static final class Entry {
        public final OptionRow view;
        public final OptionsControls.Control control;
        public final OptionsEngine.Row row;

        Entry(OptionRow view, OptionsControls.Control control, OptionsEngine.Row row) {
            this.view = view;
            this.control = control;
            this.row = row;
        }
    }

    /** `version` is the line at the foot of the side column (the game's "Version 0.4.2"). */
    public OptionsPanel(HomeUi ui, OptionsEngine model, String version, Host host) {
        super(ui.activity);
        this.ui = ui;
        this.host = host;
        this.model = model;
        this.kit = DsKit.of(ui);
        this.versionText = version;
        this.sink = new OptionsControls.Sink() {
            @Override public void step(OptionsEngine.Row r, int d) {
                focusRow(r);
                handle(model.stepSelected(d));
            }

            @Override public void range(OptionsEngine.Row r, int value) {
                focusRow(r);
                if (model.setRange(r, value)) host.onChanged(r.key);
                refresh();
            }

            @Override public void focus(OptionsEngine.Row r) {
                focusRow(r);
                refresh();
            }

            @Override public String text(String token) { return DsText.token(getContext(), token); }
        };        GameTheme t = kit.t;
        setVisibility(GONE);
        setClickable(true);  // swallows touches: nothing under the screen is reachable
        setFocusable(false);  // no default focus veil over the screen (it greyed the whole panel); rows draw their own focus
        setDefaultFocusHighlightEnabled(false);
        setBackgroundColor(t.bg);  // opaque: the home never shows through

        LinearLayout root = new LinearLayout(ui.activity);
        root.setOrientation(LinearLayout.HORIZONTAL);
        addView(root, new FrameLayout.LayoutParams(-1, -1));

        // The side column.
        List<OptionsEngine.Tab> tabs = model.tabs();
        String[][] items = new String[tabs.size()][];
        for (int i = 0; i < items.length; ++i) items[i] = new String[] {tabs.get(i).icon, res(tabs.get(i).title)};
        nav = new SideNav(kit, versionText, items);
        nav.extend(ui.gap);
        nav.setOnSelect(i -> {
            backFocused = false;
            model.selectTab(i);
            refresh();
        });
        root.addView(nav, new LinearLayout.LayoutParams(Math.round(kit.cq(DsMetrics.NAV_W)) + ui.gap.left, -1));

        // The main column: the header, then the rows.
        LinearLayout main = new LinearLayout(ui.activity);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(Math.round(kit.cq(DsMetrics.MAIN_PAD_L)), ui.gap.top, Math.round(kit.cq(DsMetrics.MAIN_PAD_R)) + ui.gap.right, ui.gap.bottom);
        root.addView(main, new LinearLayout.LayoutParams(0, -1, 1));

        LinearLayout header = new LinearLayout(ui.activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Math.round(kit.cq(DsMetrics.HEAD_PAD_X)), Math.round(kit.cq(DsMetrics.HEAD_PAD_T)),
                Math.round(kit.cq(DsMetrics.HEAD_PAD_X)), Math.round(kit.cq(DsMetrics.HEAD_PAD_B)));
        back = new IconButton(kit, "chevron-left", res("ds_back"));
        back.setOnClickListener(v -> close());
        header.addView(back, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout titles = new LinearLayout(ui.activity);
        titles.setOrientation(LinearLayout.VERTICAL);
        titleView = kit.titleLabel("", DsMetrics.T_TITLE, t.fg);
        subView = kit.label("", DsMetrics.T_DESC, t.dim);
        titles.addView(titleView, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.topMargin = Math.round(kit.cq(DsMetrics.HEAD_SUB_GAP));
        titles.addView(subView, sp);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
        tp.leftMargin = Math.round(kit.cq(DsMetrics.HEAD_TAB_GAP));
        header.addView(titles, tp);
        banner = new Notice(kit, ui, Notice.Variant.WARN).icon("refresh-cw").compact().maxWidthUnits(DsMetrics.BANNER_W);
        banner.set(res("ds_restart_banner"), "");
        banner.setVisibility(GONE);
        header.addView(banner, new LinearLayout.LayoutParams(-2, -2));
        main.addView(header, new LinearLayout.LayoutParams(-1, -2));

        rowList = new LinearLayout(ui.activity);
        rowList.setOrientation(LinearLayout.VERTICAL);
        // a margin under the last row (4% of the height): it is fully visible when scrolled to the end
        rowList.setPadding(0, 0, 0, Math.round(ui.stageH * .04f));
        rowScroll = new ScrollView(ui.activity);
        rowScroll.setVerticalScrollBarEnabled(false);
        rowScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        rowScroll.setFocusable(false);  // a focusable scroller paints the framework's grey focus veil over the rows
        rowScroll.setDefaultFocusHighlightEnabled(false);
        rowList.setDefaultFocusHighlightEnabled(false);
        root.setDefaultFocusHighlightEnabled(false);
        main.setDefaultFocusHighlightEnabled(false);
        header.setDefaultFocusHighlightEnabled(false);
        nav.setDefaultFocusHighlightEnabled(false);
        rowScroll.addView(rowList, new ScrollView.LayoutParams(-1, -2));
        main.addView(rowScroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void focusRow(OptionsEngine.Row r) {
        backFocused = false;
        model.selectRow(model.tabIndex(), model.tab().rows.indexOf(r));
    }

    // ---- Texts ----

    private String res(String name) {
        return DsText.get(getContext(), name);
    }

    /** A token from the model ("@name" parts: string resources) or a literal. */
    private String text(String token) {
        return DsText.token(getContext(), token);
    }
    private String labelOf(OptionsEngine.Row r) {
        return res(r.label());
    }

    private String subOf(OptionsEngine.Row r) {
        return res(r.sub());
    }

    // ---- Show / hide / refresh ----

    public boolean isOpen() { return getVisibility() == VISIBLE; }

    public void show() {
        model.reset();
        backFocused = false;
        shownTab = -1;
        setVisibility(VISIBLE);
        refresh();
    }

    /** Hides the panel without telling the host (the whole menu is closing). */
    public void dismiss() { setVisibility(GONE); }

    public void close() {
        if (!isOpen()) return;
        setVisibility(GONE);
        host.onClosed();
    }

    /** Rebuilds the row list when the tab changed, and repaints every value and highlight. */
    public void refresh() {
        if (shownTab != model.tabIndex()) {
            shownTab = model.tabIndex();
            rebuild();
        }
        boolean onRows = model.focusOnRows() && !backFocused;
        nav.setSelected(model.tabIndex());
        nav.setFocusedLook(!model.focusOnRows() && !backFocused);
        back.setFocusedLook(backFocused);
        for (int i = 0; i < entries.size(); ++i) {
            Entry e = entries.get(i);
            boolean on = onRows && model.rowIndex() == i;
            e.view.setFocusedRow(on);
            e.view.setTitleColor(model.disabled(e.row) && e.row.kind != OptionsEngine.Kind.INFO ? kit.t.dim : kit.t.fg);
            e.control.update();
        }
        boolean needed = host.restartNeeded();
        banner.setVisibility(needed ? VISIBLE : GONE);
        if (onRows && model.rowIndex() < entries.size()) {
            final View sel = entries.get(model.rowIndex()).view;
            rowScroll.post(() -> {
                int top = sel.getTop(), bottom = sel.getBottom(), sy = rowScroll.getScrollY(), h = rowScroll.getHeight();
                if (top < sy) rowScroll.smoothScrollTo(0, top);
                else if (bottom + rowList.getPaddingBottom() > sy + h) rowScroll.smoothScrollTo(0, bottom + rowList.getPaddingBottom() - h);
            });
        }
    }

    private void rebuild() {
        String key = model.tab().key;
        String title = res(model.tab().title);
        titleView.setText(kit.t.upperTitle ? title.toUpperCase(java.util.Locale.ROOT) : title);
        subView.setText(res(model.tab().sub));
        rowList.removeAllViews();
        entries.clear();
        if (!model.tab().warning.isEmpty()) {
            Notice w = new Notice(kit, ui, Notice.Variant.WARN).maxWidthUnits(100f);
            w.set("", res(model.tab().warning));
            LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-1, -2);
            wp.bottomMargin = Math.round(kit.cq(DsMetrics.WARN_PAD));
            rowList.addView(w, wp);
        }
        List<OptionsEngine.Row> rows = model.tab().rows;
        for (int i = 0; i < rows.size(); ++i) {
            final OptionsEngine.Row r = rows.get(i);
            List<View> badges = new ArrayList<>();
            if (r.restart) badges.add(new Badge(kit, "refresh-cw", res("ds_badge_restart"), kit.t.warn));
            if (r.experimental) badges.add(new Badge(kit, "triangle-alert", res("ds_badge_experimental"), kit.t.danger));
            OptionsControls.Control c = OptionsControls.make(kit, model, r, sink);
            OptionRow v = new OptionRow(kit, labelOf(r), subOf(r), badges, c.view(), i > 0);
            v.setOnClickListener(x -> sink.focus(r));
            entries.add(new Entry(v, c, r));
            rowList.addView(v, new LinearLayout.LayoutParams(-1, -2));
        }
        rowScroll.scrollTo(0, 0);
    }

    // ---- Input (keys, pad) ----

    /** A key or pad button; true when the panel used it (it is open, so it swallows the navigation keys). */
    public boolean onKey(int keyCode, boolean repeat) {
        if (!isOpen()) return false;
        if (backFocused) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    backFocused = false;
                    refresh();
                    return true;
                case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_SPACE:
                case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_ESCAPE: case KeyEvent.KEYCODE_BACK:
                    if (!repeat) close();
                    return true;
                case KeyEvent.KEYCODE_BUTTON_L1: case KeyEvent.KEYCODE_PAGE_UP: case KeyEvent.KEYCODE_BUTTON_R1:
                case KeyEvent.KEYCODE_PAGE_DOWN: case KeyEvent.KEYCODE_TAB:
                    backFocused = false;  // a tab change leaves Back and is handled below
                    break;
                default: return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && !model.focusOnRows() && model.tabIndex() == 0) {
            backFocused = true;  // UP from the first tab reaches Back
            refresh();
            return true;
        }
        OptionsEngine.Nav n;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: n = OptionsEngine.Nav.UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: n = OptionsEngine.Nav.DOWN; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: n = OptionsEngine.Nav.LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: n = OptionsEngine.Nav.RIGHT; break;
            case KeyEvent.KEYCODE_BUTTON_L1: case KeyEvent.KEYCODE_PAGE_UP: n = OptionsEngine.Nav.PREV_TAB; break;
            case KeyEvent.KEYCODE_BUTTON_R1: case KeyEvent.KEYCODE_PAGE_DOWN: case KeyEvent.KEYCODE_TAB: n = OptionsEngine.Nav.NEXT_TAB; break;
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_SPACE:
                if (repeat) return true;
                n = OptionsEngine.Nav.SELECT;
                break;
            case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_ESCAPE: case KeyEvent.KEYCODE_BACK:
                if (repeat) return true;
                n = OptionsEngine.Nav.BACK;
                break;
            default: return true;
        }
        handle(model.nav(n));
        return true;
    }

    /** A stick / hat motion event as D-pad presses; true when it was one. */
    public boolean onMotion(MotionEvent e) {
        if (!isOpen() || (e.getSource() & InputDevice.SOURCE_JOYSTICK) == 0 || e.getAction() != MotionEvent.ACTION_MOVE) return false;
        float y = e.getAxisValue(MotionEvent.AXIS_Y), hat = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        float x = e.getAxisValue(MotionEvent.AXIS_X), hatX = e.getAxisValue(MotionEvent.AXIS_HAT_X);
        int dir = (y < -.6f || hat < -.5f) ? -1 : (y > .6f || hat > .5f) ? 1 : 0;
        int side = (x < -.6f || hatX < -.5f) ? -1 : (x > .6f || hatX > .5f) ? 1 : 0;
        int state = dir != 0 ? dir * 10 : side;
        if (state != lastStick) {
            if (dir != 0) onKey(dir < 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN, false);
            else if (side != 0) onKey(side < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT, false);
        }
        lastStick = state;
        return true;
    }

    private void handle(OptionsEngine.Outcome o) {
        OptionsEngine.Row r = model.lastRow();
        switch (o) {
            case CLOSE: close(); return;
            case CHANGED: refresh(); if (r != null) host.onChanged(r.key); return;
            case ACTION:
                refresh();
                if (r != null) fire(r);
                return;
            default:
                refresh();
                // A muted chooser or switch (no imported driver, no support) says why instead of staying silent.
                if (r != null && (r.kind == OptionsEngine.Kind.CHOICE || r.kind == OptionsEngine.Kind.BOOL)) sayWhy(r);
        }
    }

    private void sayWhy(OptionsEngine.Row r) {
        String why = model.disabledReason(r);
        if (why != null && !why.isEmpty()) host.say(res(why));
    }

    private void fire(OptionsEngine.Row r) {
        String why = model.disabledReason(r);
        if (why != null) {
            if (!why.isEmpty()) host.say(res(why));
            return;
        }
        if (r.key.equals("reset")) confirmReset();
        else host.onAction(r.key);
    }

    private void confirmReset() {
        final String tab = model.tab().key;
        String name = res(model.tab().title);
        DsDialog.confirm(ui.activity, res("ds_reset_title"), String.format(res("ds_reset_message"), name), res("ds_cap_restore"), () -> {
            model.resetTab(tab);
            refresh();
            host.say(res("ds_say_reset"));
            host.onChanged("reset:" + tab);
        });
    }
}
