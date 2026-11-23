package io.github.belmantegu.ds.android;

import io.github.belmantegu.ds.core.*;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Statistics screen (a full screen in the design system's look, over the home): the last session (when, how
 * long, average / 1% low / worst fps, GPU frame time, render scale and a bar graph of its readings), the recent
 * sessions, the totals (play time, sessions) and this device; and the actions that go with a bug report: Copy
 * report, Share, Save diagnostics (.zip), Report a problem. Every number comes from the game's own frame log
 * (PerfLog); a value with nothing behind it says so.
 *
 * Touch, pad and keys: left / right move between the header's buttons, up / down scroll, A acts, B closes.
 */
public final class StatsScreen extends FrameLayout {
    public interface Host {
        void onCopyReport();

        void onShare();

        void onSave();

        void onReport();

        void onClosed();
    }

    /** What the screen shows (the host reads it from PerfLog and Diagnostics). */
    public static final class Data {
        public List<SessionStats.Session> history = new ArrayList<>();
        public long playTime, sessions;
        public boolean hasTotals;
        /** label -> value lines of the device card. */
        public Map<String, String> device;
        /** The frame rate the game aims at (the FPS counter's limit; 0 = auto). */
        public int limit;
    }

    private final HomeUi ui;
    private final DsKit k;
    private final Host host;
    private final LinearLayout left, right;
    private final ScrollView scroll;
    private final List<GhostButton> buttons = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();
    private int focus = -1;
    private int lastStick;

    public StatsScreen(HomeUi ui, Host host, boolean report) {
        super(ui.activity);
        this.ui = ui;
        this.k = DsKit.of(ui);
        this.host = host;
        setBackgroundColor(k.t.bg);
        setClickable(true);
        setVisibility(GONE);
        FrameLayout stage = ui.stage(this);

        // header: icon + title on the left, the actions on the right
        LinearLayout head = new LinearLayout(ui.activity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(new IconView(ui.activity, "activity", k.t.accent), new LinearLayout.LayoutParams(ui.px(DsMetrics.ICON_L), ui.px(DsMetrics.ICON_L)));
        TextView title = k.titleLabel(str("ds_stats_title"), DsMetrics.T_TITLE, k.t.fg);
        title.setPadding(ui.px(DsMetrics.ICON_GAP), 0, 0, 0);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        addButton(head, "copy", str("ds_stats_copy"), host::onCopyReport, false);
        addButton(head, "share-2", str("ds_stats_share"), host::onShare, false);
        addButton(head, "download", str("ds_stats_save"), host::onSave, false);
        if (report) addButton(head, "heart", str("ds_stats_report"), host::onReport, false);
        addButton(head, "x", str("ds_close"), this::close, true);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-1, ui.px(DsMetrics.STATS_HEAD_H), Gravity.TOP);
        hp.leftMargin = hp.rightMargin = ui.px(DsMetrics.MARGIN);
        hp.topMargin = ui.px(DsMetrics.S2);
        stage.addView(head, hp);

        scroll = new ScrollView(ui.activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        LinearLayout cols = new LinearLayout(ui.activity);
        left = column();
        right = column();
        cols.addView(left, new LinearLayout.LayoutParams(ui.px(DsMetrics.STATS_LEFT_W), -2));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1f);
        rp.leftMargin = ui.px(DsMetrics.S3);
        cols.addView(right, rp);
        scroll.addView(cols, new FrameLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, -1);
        sp.topMargin = ui.px(DsMetrics.S2 + DsMetrics.STATS_HEAD_H + DsMetrics.S2);
        sp.leftMargin = sp.rightMargin = ui.px(DsMetrics.MARGIN);
        sp.bottomMargin = ui.px(DsMetrics.S3);
        stage.addView(scroll, sp);
    }

    private String str(String name) { return DsText.get(ui.activity, name); }

    private void addButton(LinearLayout row, String icon, String caption, Runnable action, boolean last) {
        GhostButton b = new GhostButton(k, DsMetrics.S, false);
        b.set(icon, caption);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = ui.px(DsMetrics.S2);
        row.addView(b, lp);
        buttons.add(b);
        actions.add(action);
    }

    private LinearLayout column() {
        LinearLayout c = new LinearLayout(ui.activity);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    private LinearLayout card(LinearLayout parent, String title, String sub) {
        LinearLayout c = column();
        GradientDrawable g = new GradientDrawable();
        g.setColor(k.t.surface);
        g.setCornerRadius(k.cq(k.t.rMd));
        g.setStroke(Math.max(1, Math.round(k.cq(DsMetrics.HAIRLINE))), k.t.line);
        c.setBackground(g);
        int pad = ui.px(DsMetrics.S3);
        c.setPadding(pad, pad, pad, pad);
        LinearLayout head = new LinearLayout(ui.activity);
        head.setGravity(Gravity.BOTTOM);
        head.addView(k.titleLabel(title, DsMetrics.T_ITEM, k.t.fg));
        if (sub != null && !sub.isEmpty()) {
            TextView s = k.label(sub, DsMetrics.T_DESC, k.t.dim);
            s.setPadding(ui.px(DsMetrics.S2), 0, 0, 0);
            head.addView(s, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        c.addView(head);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = ui.px(DsMetrics.S3);
        parent.addView(c, lp);
        return c;
    }

    private void row(LinearLayout card, String label, String value, int color) {
        LinearLayout r = new LinearLayout(ui.activity);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, ui.px(DsMetrics.S1), 0, ui.px(DsMetrics.S1));
        r.addView(k.label(label, DsMetrics.T_DESC, k.t.dim), new LinearLayout.LayoutParams(ui.px(DsMetrics.DIALOG_ROW_LABEL_W), -2));
        TextView v = k.label(value, DsMetrics.T_DESC, color);
        r.addView(v, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = ui.px(DsMetrics.S1);
        card.addView(r, lp);
    }

    private View tile(String value, String label, int color) {
        LinearLayout t = column();
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView v = k.titleLabel(value, DsMetrics.STATS_VALUE, color);
        v.setGravity(Gravity.CENTER);
        t.addView(v);
        TextView l = k.label(label, DsMetrics.T_DESC, k.t.dim);
        l.setGravity(Gravity.CENTER);
        l.setPadding(0, ui.px(DsMetrics.S1), 0, 0);
        t.addView(l);
        return t;
    }

    /** An equal share of a row. */
    private static LinearLayout.LayoutParams share() { return new LinearLayout.LayoutParams(0, -2, 1f); }

    private static String f1(float v) { return String.format(Locale.US, "%.1f", v); }

    /** Fills the cards. */
    public void set(Data d) {
        left.removeAllViews();
        right.removeAllViews();
        GameTheme t = k.t;
        DateFormat when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault());
        String none = str("ds_stats_none");
        SessionStats.Session last = d.history.isEmpty() ? null : d.history.get(0);
        LinearLayout s = card(left, str("ds_stats_session"), last == null ? "" : when.format(new Date(last.startMs)) + " · " + SessionStats.duration(last.seconds));
        if (last == null || !last.hasFrames()) {
            TextView e = k.label(str("ds_stats_empty"), DsMetrics.T_BODY, t.dim);
            e.setPadding(0, ui.px(DsMetrics.S2), 0, 0);
            s.addView(e);
        } else {
            LinearLayout tiles = new LinearLayout(ui.activity);
            tiles.setPadding(0, ui.px(DsMetrics.S3), 0, ui.px(DsMetrics.S2));
            int c = levelColor(FpsStats.level(last.avg, d.limit));
            tiles.addView(tile(f1(last.avg), str("ds_stats_avg"), c), share());
            tiles.addView(tile(last.low > 0 ? f1(last.low) : none, str("ds_stats_low"), levelColor(FpsStats.level(last.low, d.limit))), share());
            tiles.addView(tile(f1(last.worst), str("ds_stats_worst"), levelColor(FpsStats.level(last.worst, d.limit))), share());
            tiles.addView(tile(last.gpu > 0 ? String.format(Locale.US, "%.1f ms", last.gpu) : none, str("ds_stats_gpu"), t.fg), share());
            tiles.addView(tile(last.scale > 0 ? Math.round(last.scale * 100) + "%" : none, str("ds_stats_scale"), t.fg), share());
            s.addView(tiles);
            Graph g = new Graph(last.graph, d.limit);
            s.addView(g, new LinearLayout.LayoutParams(-1, ui.px(DsMetrics.STATS_GRAPH_H)));
            TextView n = k.label(String.format(str("ds_stats_graph_note"), last.windows), DsMetrics.T_BADGE, t.dim);
            n.setPadding(0, ui.px(DsMetrics.S2), 0, 0);
            s.addView(n);
        }

        LinearLayout h = card(right, str("ds_stats_history"), "");
        if (d.history.isEmpty()) row(h, "", none, t.dim);
        for (int i = 0; i < Math.min(8, d.history.size()); ++i) {
            SessionStats.Session x = d.history.get(i);
            String v = SessionStats.duration(x.seconds) + (x.hasFrames() ? String.format(Locale.US, " · %.1f fps · 1%% %.1f", x.avg, x.low) : " · " + none);
            row(h, DateFormat.getDateInstance(DateFormat.SHORT).format(new Date(x.startMs)) + " " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(x.startMs)),
                    v, x.hasFrames() ? levelColor(FpsStats.level(x.avg, d.limit)) : t.dim);
        }
        LinearLayout tot = card(right, str("ds_stats_total"), "");
        row(tot, str("ds_stats_playtime"), d.hasTotals ? SessionStats.duration(d.playTime) : none, t.fg);
        row(tot, str("ds_stats_sessions"), d.hasTotals ? String.valueOf(d.sessions) : none, t.fg);
        if (d.device != null && !d.device.isEmpty()) {
            LinearLayout dev = card(left, str("ds_stats_device"), "");
            for (Map.Entry<String, String> e : d.device.entrySet()) row(dev, e.getKey(), e.getValue(), t.fg);
        }
        TextView note = k.label(str("ds_stats_note"), DsMetrics.T_BADGE, t.dim);
        left.addView(note);
    }

    private int levelColor(int level) {
        return level == FpsStats.GOOD ? k.t.ok : level == FpsStats.FAIR ? GameTheme.CAUTION : GameTheme.CRITICAL;
    }

    /** The session's readings, one bar each (oldest left), against the larger of 60 and the highest one. */
    private final class Graph extends View {
        private final float[] v;
        private final int limit;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Graph(float[] values, int limit) {
            super(ui.activity);
            v = values;
            this.limit = limit;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float top = 60;
            for (float x : v) top = Math.max(top, x);
            p.setColor(GameTheme.TRACK);
            k.rect(c, 0, 0, w, h, k.t.rSm, p);
            int n = Math.max(v.length, 30);
            float bar = w / n, gap = Math.min(k.cq(DsMetrics.FPS_BAR_GAP), bar / 3f);
            for (int i = 0; i < v.length; ++i) {
                float bh = Math.max(k.cq(DsMetrics.FPS_BAR_MIN), h * v[i] / top);
                p.setColor(levelColor(FpsStats.level(v[i], limit)));
                c.drawRect(i * bar, h - bh, i * bar + bar - gap, h, p);
            }
            // the 60 and 30 lines
            p.setColor(GameTheme.GLINT);
            for (float mark : new float[] {60, 30}) {
                float y = h - h * mark / top;
                c.drawRect(0, y, w, y + Math.max(1, k.cq(DsMetrics.HAIRLINE)), p);
            }
        }
    }

    // ---- Open, close, keys ----

    public boolean isOpen() { return getVisibility() == VISIBLE; }

    public void show(Data d) {
        set(d);
        focus = -1;
        mark();
        scroll.scrollTo(0, 0);
        setVisibility(VISIBLE);
        bringToFront();
    }

    public void close() {
        if (!isOpen()) return;
        setVisibility(GONE);
        host.onClosed();
    }

    private void mark() {
        for (int i = 0; i < buttons.size(); ++i) buttons.get(i).setOn(i == focus);
    }

    public boolean onKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_ESCAPE: close(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: focus = focus <= 0 ? 0 : focus - 1; mark(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: focus = Math.min(buttons.size() - 1, focus + 1); mark(); return true;
            case KeyEvent.KEYCODE_DPAD_UP: scroll.smoothScrollBy(0, -ui.px(DsMetrics.DIALOG_STEP)); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: scroll.smoothScrollBy(0, ui.px(DsMetrics.DIALOG_STEP)); return true;
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_DPAD_CENTER:
                if (focus >= 0 && focus < actions.size()) actions.get(focus).run();
                return true;
            default: return true;
        }
    }

    public boolean onMotion(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_MOVE) return false;
        float x = e.getAxisValue(MotionEvent.AXIS_X), hx = e.getAxisValue(MotionEvent.AXIS_HAT_X);
        float y = e.getAxisValue(MotionEvent.AXIS_Y), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        int dir = (x < -.6f || hx < -.5f) ? 1 : (x > .6f || hx > .5f) ? 2 : (y < -.6f || hy < -.5f) ? 3 : (y > .6f || hy > .5f) ? 4 : 0;
        if (dir != lastStick && dir != 0) {
            onKey(dir == 1 ? KeyEvent.KEYCODE_DPAD_LEFT : dir == 2 ? KeyEvent.KEYCODE_DPAD_RIGHT : dir == 3 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN);
        }
        lastStick = dir;
        return true;
    }
}
