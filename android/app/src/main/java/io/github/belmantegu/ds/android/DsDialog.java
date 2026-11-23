package io.github.belmantegu.ds.android;

import io.github.belmantegu.ds.core.*;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The one dialog of the app, in the design system (documentation/guides/DESIGN_SYSTEM.md): an opaque
 * sheet with the theme's radius over a solid screen, a title (with an optional Lucide icon), a
 * scrollable message or label / value rows with hairlines (row(), rowAction()), and M-size buttons
 * (the first one primary) of one width per group, with the pad focus ring. Nothing of the system's
 * AlertDialog and nothing of the old framed plates. Touch works, and so do the D-pad /
 * stick / keyboard: up and down move between rows (or scroll a long text),
 * left and right move inside a row, A / Enter confirms, B / Esc / Back
 * cancels.
 *
 * Variations: alert (OK), confirm, confirmDestructive (focus starts on
 * Cancel), choose (a list), progress (busy, no buttons), and with() for the
 * rest (stacked buttons, a long scrolling text).
 */
public final class DsDialog {
    private DsDialog() {}

    // ---- the quick ones ----

    /** A notice with one OK. */
    public static void alert(Activity a, String message) {
        alert(a, null, message, null);
    }

    public static void alert(Activity a, String title, String message, Runnable then) {
        with(a).small().title(title).message(message).button(a.getString(android.R.string.ok), then).show();
    }

    /** Two buttons. The first runs {@code yes}; Back / B / the second button only close. */
    public static void confirm(Activity a, String title, String message, String yesLabel, Runnable yes) {
        with(a).small().title(title).message(message).button(yesLabel, yes).button(a.getString(android.R.string.cancel), null).show();
    }

    /** A destructive confirmation: the focus starts on Cancel. */
    public static void confirmDestructive(Activity a, String title, String message, String yesLabel, Runnable yes) {
        with(a).small().title(title).message(message).button(yesLabel, yes).button(a.getString(android.R.string.cancel), null)
                .defaultButton(1).show();
    }

    /** A list of options; {@code pick} gets the index. */
    public static void choose(Activity a, String title, String[] labels, int checked, IntConsumer pick) {
        with(a).small().title(title).choices(labels, checked, pick).show();
    }

    /** A modal busy box: no buttons, not cancelable. Dismiss it from the UI thread. */
    public static Handle progress(Activity a, String title, String message) {
        return with(a).title(title).message(message).progressBar().cancelable(false).show();
    }

    public static Builder with(Activity a) {
        return new Builder(a);
    }

    // ---- the handle ----

    public static final class Handle {
        private final Dialog dialog;
        private TextView message;
        private HomeUi.ProgressLine line;
        private ValueAnimator sweep;

        private Handle(Dialog d) { dialog = d; }

        public boolean showing() { return dialog.isShowing(); }

        public void setMessage(String s) {
            if (message != null) message.setText(s);
        }

        /** 0..1, or negative for "working, no end in sight". */
        public void setProgress(float f) {
            if (line == null) return;
            if (f < 0) {
                if (sweep == null) {
                    sweep = ValueAnimator.ofFloat(0f, 1f);
                    sweep.setDuration(1100);
                    sweep.setRepeatCount(ValueAnimator.INFINITE);
                    sweep.setRepeatMode(ValueAnimator.REVERSE);
                    sweep.addUpdateListener(v -> line.set((float) v.getAnimatedValue()));
                    sweep.start();
                }
            } else {
                if (sweep != null) { sweep.cancel(); sweep = null; }
                line.set(f);
            }
        }

        public void dismiss() {
            if (sweep != null) { sweep.cancel(); sweep = null; }
            try {
                if (dialog.isShowing()) dialog.dismiss();
            } catch (RuntimeException ignored) {
                // the activity is already gone
            }
        }
    }

    // ---- the pieces ----

    /** A ScrollView that never grows past a limit the panel sets. */
    private static final class MaxScroll extends ScrollView {
        int max = Integer.MAX_VALUE;

        MaxScroll(Context c) {
            super(c);
            setFocusable(false);
            setVerticalScrollBarEnabled(false);
        }

        @Override
        protected void onMeasure(int w, int h) {
            int size = MeasureSpec.getSize(h);
            int cap = MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED ? max : Math.min(max, size);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
        }
    }

    /** The plate: measured once freely, then the scrolling part gives up what does not fit the screen. */
    private static final class Panel extends LinearLayout {
        MaxScroll scroll;
        int screenH, minScroll;

        Panel(Context c) { super(c); }

        @Override
        protected void onMeasure(int w, int h) {
            if (scroll == null) { super.onMeasure(w, h); return; }
            scroll.max = Integer.MAX_VALUE;
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int over = getMeasuredHeight() - screenH;
            if (over > 0) {
                scroll.max = Math.max(minScroll, scroll.getMeasuredHeight() - over);
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            }
        }
    }

    /** One focusable thing: its view, what A does, and how to show the focus. */
    private static final class Item {
        View view;
        Runnable act;
        java.util.function.Consumer<Boolean> mark;
    }

    // ---- the builder ----

    public static final class Builder {
        private final Activity act;
        private final HomeUi ui;
        private final DsKit kit;
        private final float sw, sh;
        private String icon;
        private LinearLayout rowsBox;
        private final Typeface display, face;
        private String title;
        private float dialogWidth = DsMetrics.DIALOG_W_M;
        private boolean wide, cancelable = true, progress, stackButtons;
        private Runnable onBack;
        private int defaultButton;
        private final List<View> parts = new ArrayList<>();      // fixed views, in order
        private int scrollAt = -1;
        private final List<List<Item>> groups = new ArrayList<>();
        private final List<Runnable> actions = new ArrayList<>();
        private final List<String> labels = new ArrayList<>();
        private final List<String> icons = new ArrayList<>();
        private int initialGroup = -1, initialIndex;
        private TextView messageView;
        private Handle handle;
        private Dialog dialog;

        private Builder(Activity a) {
            act = a;
            ui = new HomeUi(a);
            kit = DsKit.of(ui);
            sw = ui.stageW;  // a dialog is sized on the stage, not on the whole (maybe very wide) screen
            sh = ui.stageH;
            display = ui.display;
            face = ui.ui;
        }

        private int px(float units) { return ui.px(units); }

        private TextView label(String s, float size, int color, Typeface f) {
            TextView t = new TextView(act);
            t.setText(s);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * ui.u);
            t.setTextColor(color);
            t.setTypeface(f);
            t.setIncludeFontPadding(false);
            return t;
        }

        public Builder title(String t) { title = t; return this; }

        /** A Lucide icon before the title. */
        public Builder icon(String name) { icon = name; return this; }

        private LinearLayout rows() {
            if (rowsBox == null) {
                rowsBox = new LinearLayout(act);
                rowsBox.setOrientation(LinearLayout.VERTICAL);
                scrollAt = parts.size();
                parts.add(rowsBox);
            }
            return rowsBox;
        }

        private LinearLayout rowView(String label, String value, int valueColor, GhostButton action) {
            GameTheme t = kit.t;
            LinearLayout row = new LinearLayout(act);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, px(DsMetrics.S2), 0, px(DsMetrics.S2));
            TextView l = kit.label(label, DsMetrics.T_DESC, t.dim);
            row.addView(l, new LinearLayout.LayoutParams(px(DsMetrics.DIALOG_ROW_LABEL_W), -2));
            TextView v = kit.label(value, DsMetrics.T_BODY, valueColor);
            v.setLineSpacing(0, 1.08f);
            row.addView(v, new LinearLayout.LayoutParams(0, -2, 1f));
            if (action != null) {
                LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
                ap.leftMargin = px(DsMetrics.S3);
                row.addView(action, ap);
            }
            return row;
        }

        private void hairline() {
            View line = new View(act);
            line.setBackgroundColor(kit.t.line);
            rows().addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, Math.round(kit.cq(DsMetrics.HAIRLINE)))));
        }

        /** A label / value line with a hairline under it (the sheet's sections). */
        public Builder row(String label, String value, int valueColor) {
            rows().addView(rowView(label, value, valueColor, null));
            hairline();
            return this;
        }

        public Builder row(String label, String value) { return row(label, value, kit.t.fg); }

        /** A row with a small button (S size, focusable) at its end: "Copy" on the folder path. */
        public Builder rowAction(String label, String value, String iconName, String caption, Runnable action) {
            GhostButton b = new GhostButton(kit, DsMetrics.S, false).raised();
            b.set(iconName, caption);
            rows().addView(rowView(label, value, kit.t.fg, b));
            hairline();
            addGroup(buttonItem(b, action));
            return this;
        }

        public Builder wide() { wide = true; return this; }

        /** The standard widths: S (40 units, a confirmation) or M (56, the default). */
        public Builder small() { dialogWidth = DsMetrics.DIALOG_W_S; return this; }

        public Builder cancelable(boolean c) { cancelable = c; return this; }

        /** Runs when the dialog is cancelled with Back / B. */
        public Builder onBack(Runnable r) { onBack = r; return this; }

        public Builder defaultButton(int i) { defaultButton = i; return this; }

        public Builder stackButtons() { stackButtons = true; return this; }

        /** The main text, scrolling when long. */
        public Builder message(String s) {
            if (s == null) return this;
            messageView = label(s, DsMetrics.T_BODY, kit.t.fg, face);
            messageView.setLineSpacing(0, 1.08f);
            setScroll(messageView, px(DsMetrics.DIALOG_MSG_TOP), 0);
            return this;
        }

        /** A long text to read (a log, a report), in a smaller type on a darker well. */
        public Builder scrollText(String s) {
            TextView t = label(s, DsMetrics.T_DESC, kit.t.fg, face);
            t.setLineSpacing(0, 1.05f);
            GradientDrawable g = new GradientDrawable();
            g.setColor(kit.t.bg);
            g.setCornerRadius(kit.cq(kit.t.rSm));
            t.setBackground(g);
            t.setPadding(px(DsMetrics.DIALOG_WELL_X), px(DsMetrics.DIALOG_WELL_Y), px(DsMetrics.DIALOG_WELL_X), px(DsMetrics.DIALOG_WELL_Y));
            setScroll(t, px(DsMetrics.DIALOG_NOTE_PAD), px(DsMetrics.DIALOG_NOTE_PAD));
            return this;
        }

        private void setScroll(View v, int top, int bottom) {
            LinearLayout box = new LinearLayout(act);
            box.addView(v, new LinearLayout.LayoutParams(-1, -2));
            box.setPadding(0, top, 0, bottom);
            scrollAt = parts.size();
            parts.add(box);
        }

        /** A fixed line of text above or below the scrolling part. */
        public Builder text(String s) {
            TextView t = label(s, DsMetrics.T_BADGE, kit.t.fg, face);
            t.setPadding(0, px(DsMetrics.DIALOG_TEXT_PAD), 0, px(DsMetrics.DIALOG_TEXT_PAD));
            parts.add(t);
            return this;
        }

        public Builder note(String s) {
            TextView t = label(s, DsMetrics.T_BADGE, kit.t.dim, face);
            t.setPadding(0, px(DsMetrics.DIALOG_NOTE_PAD), 0, 0);
            parts.add(t);
            return this;
        }

        /** A list of options as buttons, each its own row; the checked one starts focused. */
        public Builder choices(String[] names, int checked, IntConsumer pick) {
            LinearLayout list = new LinearLayout(act);
            list.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < names.length; ++i) {
                final int index = i;
                GhostButton b = new GhostButton(kit, DsMetrics.M, false).raised();
                b.set(i == checked ? "check" : null, names[i]);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = px(DsMetrics.S1);
                list.addView(b, lp);
                addGroup(buttonItem(b, () -> { close(); pick.accept(index); }));
                if (i == checked) { initialGroup = groups.size() - 1; initialIndex = 0; }
            }
            LinearLayout box = new LinearLayout(act);
            box.addView(list, new LinearLayout.LayoutParams(-1, -2));
            scrollAt = parts.size();
            parts.add(box);
            return this;
        }

        public Builder progressBar() { progress = true; return this; }

        /** A button; it closes the dialog, then runs {@code action} (may be null). */
        public Builder button(String label, Runnable action) {
            return button(null, label, action);
        }

        /** A button with a Lucide icon before its caption. */
        public Builder button(String iconName, String label, Runnable action) {
            icons.add(iconName);
            labels.add(label);
            actions.add(action);
            return this;
        }

        private void addGroup(Item it) {
            List<Item> g = new ArrayList<>();
            g.add(it);
            groups.add(g);
        }

        private Item buttonItem(GhostButton b, Runnable action) {
            Item it = new Item();
            it.view = b;
            it.act = action;
            it.mark = b::setOn;
            b.setOnClickListener(v -> action.run());
            return it;
        }

        private void close() {
            if (handle != null) handle.dismiss();
        }

        // ---- showing ----

        public Handle show() {
            dialog = new Dialog(act, DsText.style(act, "ds_DialogTheme") != 0 ? DsText.style(act, "ds_DialogTheme")
                    : android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen) {
                @Override
                public boolean dispatchKeyEvent(KeyEvent e) {
                    return keys(e) || super.dispatchKeyEvent(e);
                }

                @Override
                public boolean onGenericMotionEvent(MotionEvent e) {
                    return stick(e) || super.onGenericMotionEvent(e);
                }
            };
            handle = new Handle(dialog);
            dialog.setCancelable(false);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            FrameLayout root = new FrameLayout(act);
            root.setBackgroundColor(kit.t.bg);  // solid: nothing of what is behind shows
            root.setClickable(true);

            Panel panel = new Panel(act);
            panel.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable solid = new GradientDrawable();
            solid.setColor(kit.t.surface);
            solid.setCornerRadius(kit.cq(kit.t.rLg));
            solid.setStroke(Math.max(1, Math.round(kit.cq(DsMetrics.HAIRLINE))), kit.t.line);
            panel.setBackground(solid);
            panel.setPadding(px(DsMetrics.S4), px(DsMetrics.S3 + DsMetrics.S1), px(DsMetrics.S4), px(DsMetrics.S4 - DsMetrics.S1));
            panel.setClickable(true);
            panel.setElevation(kit.cq(DsMetrics.ELEVATION));

            if (title != null && !title.isEmpty()) {
                LinearLayout head = new LinearLayout(act);
                head.setGravity(Gravity.CENTER_VERTICAL);
                if (icon != null) {
                    IconView iv = new IconView(act, icon, kit.t.accent);
                    head.addView(iv, new LinearLayout.LayoutParams(px(DsMetrics.M.icon + .4f), px(DsMetrics.M.icon + .4f)));
                }
                TextView t = kit.titleLabel(title, DsMetrics.T_ITEM, kit.t.fg);
                t.setPadding(icon != null ? px(DsMetrics.ICON_GAP) : 0, 0, 0, 0);
                head.addView(t);
                LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
                hp.bottomMargin = px(DsMetrics.S3);
                panel.addView(head, hp);
            }
            MaxScroll scroll = null;
            for (int i = 0; i < parts.size(); ++i) {
                View v = parts.get(i);
                if (i == scrollAt) {
                    scroll = new MaxScroll(act);
                    scroll.addView(v, new FrameLayout.LayoutParams(-1, -2));
                    panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
                    scrollView = scroll;
                } else if (v.getLayoutParams() != null) {
                    panel.addView(v);
                } else {
                    panel.addView(v, new LinearLayout.LayoutParams(-1, -2));
                }
            }
            if (progress) {
                HomeUi.ProgressLine line = ui.new ProgressLine();
                line.set(0);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(DsMetrics.DIALOG_PROGRESS_H));
                lp.topMargin = px(DsMetrics.S2);
                panel.addView(line, lp);
                handle.line = line;
                handle.setProgress(-1);
            }
            handle.message = messageView;

            if (!labels.isEmpty()) {
                LinearLayout row = new LinearLayout(act);
                row.setOrientation(stackButtons ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
                if (labels.size() == 1 && !stackButtons) row.setGravity(Gravity.END);
                List<Item> group = new ArrayList<>();
                for (int i = 0; i < labels.size(); ++i) {
                    final Runnable action = actions.get(i);
                    GhostButton b = new GhostButton(kit, DsMetrics.M, i == 0);
                    if (i > 0) b.raised();
                    b.set(icons.get(i), labels.get(i));
                    // one button keeps its own width at the end of the row; several share the row equally
                    LinearLayout.LayoutParams lp = stackButtons ? new LinearLayout.LayoutParams(-1, -2)
                            : labels.size() == 1 ? new LinearLayout.LayoutParams(-2, -2) : new LinearLayout.LayoutParams(0, -2, 1);
                    if (stackButtons) lp.topMargin = px(DsMetrics.S1);
                    else { lp.leftMargin = px(DsMetrics.S1); lp.rightMargin = px(DsMetrics.S1); }
                    row.addView(b, lp);
                    Item it = buttonItem(b, () -> { close(); if (action != null) action.run(); });
                    if (stackButtons) addGroup(it);
                    else group.add(it);
                }
                if (!stackButtons) groups.add(group);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
                rowLp.topMargin = px(DsMetrics.S3);
                panel.addView(row, rowLp);
                int first = groups.size() - (stackButtons ? labels.size() : 1);
                if (initialGroup < 0) {
                    initialGroup = stackButtons ? first + Math.min(defaultButton, labels.size() - 1) : first;
                    initialIndex = stackButtons ? 0 : Math.min(defaultButton, labels.size() - 1);
                }
            }
            if (initialGroup < 0 && !groups.isEmpty()) { initialGroup = 0; initialIndex = 0; }

            panel.scroll = scroll;
            panel.screenH = Math.round(sh * DsMetrics.DIALOG_HEIGHT_FRAC);
            panel.minScroll = px(DsMetrics.DIALOG_MIN_SCROLL);
            int width = wide ? Math.round(sw * DsMetrics.DIALOG_WIDE_FRAC) : Math.min(px(dialogWidth), Math.round(sw * DsMetrics.DIALOG_MAX_FRAC));
            FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(width, -2, Gravity.CENTER);
            panelLp.setMargins(ui.gap.left, ui.gap.top, ui.gap.right, ui.gap.bottom);  // centred on the stage
            root.addView(panel, panelLp);
            dialog.setContentView(root);
            Window w = dialog.getWindow();
            if (w != null) {
                w.setLayout(-1, -1);
                w.setBackgroundDrawable(new ColorDrawable(kit.t.bg));
            }
            if (act.isFinishing() || act.isDestroyed()) return handle;
            g = Math.max(0, initialGroup);
            idx = initialIndex;
            try {
                dialog.show();
            } catch (RuntimeException e) {
                return handle;
            }
            immersive();
            mark(true);
            return handle;
        }

        private void immersive() {
            Window w = dialog.getWindow();
            if (w == null) return;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = w.getDecorView().getWindowInsetsController();
                if (c != null) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            }
        }

        // ---- the focus ----

        private int g, idx;
        private int lastStick;
        private MaxScroll scrollView;

        private Item current() {
            if (groups.isEmpty()) return null;
            g = Math.max(0, Math.min(groups.size() - 1, g));
            List<Item> row = groups.get(g);
            idx = Math.max(0, Math.min(row.size() - 1, idx));
            return row.get(idx);
        }

        private void mark(boolean on) {
            Item it = current();
            if (it == null) return;
            it.mark.accept(on);
            if (on && scrollView != null && it.view.getParent() != null) {
                View p = (View) it.view.getParent();
                if (p.getParent() == scrollView.getChildAt(0) || p.getParent() == scrollView) {
                    final int top = it.view.getTop() + p.getTop();
                    scrollView.post(() -> scrollView.smoothScrollTo(0, Math.max(0, top - px(DsMetrics.DIALOG_SCROLL_LEAD))));
                }
            }
        }

        private void move(int dx, int dy) {
            Item cur = current();
            if (dy != 0) {
                if (scrollView != null && scrollHasNoItems() && scrollView.canScrollVertically(dy)) {
                    scrollView.smoothScrollBy(0, dy * Math.max(px(DsMetrics.DIALOG_STEP), scrollView.getHeight() * 6 / 10));
                    return;
                }
                if (cur == null) return;
                int n = Math.max(0, Math.min(groups.size() - 1, g + dy));
                if (n == g) return;
                mark(false);
                g = n;
                idx = Math.min(idx, groups.get(g).size() - 1);
                mark(true);
            } else if (dx != 0 && cur != null) {
                int n = Math.max(0, Math.min(groups.get(g).size() - 1, idx + dx));
                if (n == idx) return;
                mark(false);
                idx = n;
                mark(true);
            }
        }

        // choose(): the scrolling part holds the buttons themselves; the focus scrolls it.
        private boolean scrollHasNoItems() {
            for (List<Item> row : groups) {
                for (Item it : row) {
                    View p = (View) it.view.getParent();
                    if (p != null && p.getParent() != null && p.getParent().getParent() == scrollView) return false;
                    if (p != null && p.getParent() == scrollView) return false;
                }
            }
            return true;
        }

        private void cancel() {
            if (!cancelable) return;
            close();
            if (onBack != null) onBack.run();
        }

        private boolean keys(KeyEvent e) {
            int k = e.getKeyCode();
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_SPACE:
                case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_ESCAPE: case KeyEvent.KEYCODE_BACK:
                    break;
                default:
                    return false;
            }
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_UP: move(0, -1); break;
                case KeyEvent.KEYCODE_DPAD_DOWN: move(0, 1); break;
                case KeyEvent.KEYCODE_DPAD_LEFT: move(-1, 0); break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: move(1, 0); break;
                case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_ESCAPE: case KeyEvent.KEYCODE_BACK:
                    if (e.getRepeatCount() == 0) cancel();
                    break;
                default:
                    if (e.getRepeatCount() == 0) {
                        Item it = current();
                        if (it != null && it.act != null) it.act.run();
                    }
                    break;
            }
            return true;
        }

        private boolean stick(MotionEvent e) {
            if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) == 0 || e.getAction() != MotionEvent.ACTION_MOVE) return false;
            float y = e.getAxisValue(MotionEvent.AXIS_Y), hat = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            float x = e.getAxisValue(MotionEvent.AXIS_X), hatX = e.getAxisValue(MotionEvent.AXIS_HAT_X);
            int dir = (y < -.6f || hat < -.5f) ? -1 : (y > .6f || hat > .5f) ? 1 : 0;
            int side = (x < -.6f || hatX < -.5f) ? -1 : (x > .6f || hatX > .5f) ? 1 : 0;
            int state = dir != 0 ? dir * 10 : side;
            if (state != lastStick) {
                if (dir != 0) move(0, dir);
                else if (side != 0) move(side, 0);
            }
            lastStick = state;
            return true;
        }
    }
}
