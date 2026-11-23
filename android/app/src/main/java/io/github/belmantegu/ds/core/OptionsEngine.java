package io.github.belmantegu.ds.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The tabs and rows of an Options screen, their values and the pad / key / touch navigation (pure Java,
 * no android imports; the picture is the OptionsPanel). It is generic: a game gives it TabSpecs (data), a
 * ValueStore (where values live) and an OptionsEnv (visibility flags, live lists, why a row is muted).
 * Layout: tabs on the left, the rows of the selected tab on the right, a row being a chooser, a switch, a
 * slider (RANGE), an action button or a read-only line; each tab ends with "Restore defaults" when the
 * game declares that row. valueText() returns a literal ("75%") or "@name", a string resource.
 */
public class OptionsEngine {
    public enum Kind { CHOICE, BOOL, RANGE, ACTION, INFO }
    public enum Nav { UP, DOWN, LEFT, RIGHT, SELECT, BACK, PREV_TAB, NEXT_TAB }
    public enum Outcome { NONE, MOVED, CHANGED, ACTION, CLOSE }

    /** A visible row: the spec's fields and a way back to the spec. */
    public static final class Row {
        public final String key;
        public final Kind kind;
        /** Applies only when the game restarts. */
        public final boolean restart;
        /** Experimental: shown with a warning. */
        public final boolean experimental;
        public final int min, max, step;
        public final OptionSpec spec;

        Row(OptionSpec s) {
            this.spec = s;
            this.key = s.key;
            this.kind = s.kind;
            this.restart = s.restart;
            this.experimental = s.experimental;
            this.min = s.min;
            this.max = s.max;
            this.step = s.step;
        }

        /** The string-resource names of the label and the description. */
        public String label() { return spec.label; }

        public String sub() { return spec.sub; }

        public String icon() { return spec.icon; }
    }

    public static final class Tab {
        public final String key, icon, title, sub, warning;
        public final List<Row> rows;

        Tab(TabSpec t, List<Row> rows) {
            this.key = t.key;
            this.icon = t.icon;
            this.title = t.title;
            this.sub = t.sub;
            this.warning = t.warning;
            this.rows = rows;
        }
    }

    protected final ValueStore store;
    protected final OptionsEnv env;
    private final List<Tab> tabs = new ArrayList<>();
    private int tab, row;
    private boolean inRows;
    private Row lastRow;

    public OptionsEngine(List<TabSpec> specs, ValueStore store, OptionsEnv env) {
        this.store = store;
        this.env = env;
        for (TabSpec t : specs) {
            List<Row> rows = new ArrayList<>();
            for (OptionSpec s : t.rows) if (s.when.isEmpty() || env.flag(s.when)) rows.add(new Row(s));
            if (!rows.isEmpty()) tabs.add(new Tab(t, rows));
        }
    }

    // ---- Structure ----

    public List<Tab> tabs() { return Collections.unmodifiableList(tabs); }
    public Tab tab() { return tabs.get(tab); }
    public int tabIndex() { return tab; }
    public int rowIndex() { return row; }
    public boolean focusOnRows() { return inRows; }
    public Row selectedRow() { return tabs.get(tab).rows.get(row); }
    /** The row the last nav() call stepped or fired (null after a move). */
    public Row lastRow() { return lastRow; }
    public OptionsEnv env() { return env; }

    public Row find(String key) {
        for (Tab t : tabs) for (Row r : t.rows) if (r.key.equals(key)) return r;
        return null;
    }

    /** Every row key of every tab, once (the tests check that each has a text). */
    public List<String> allKeys() {
        List<String> keys = new ArrayList<>();
        for (Tab t : tabs) for (Row r : t.rows) if (!keys.contains(r.key)) keys.add(r.key);
        return keys;
    }

    // ---- Values ----

    private List<OptionSpec.Choice> choicesOf(Row r) {
        return r.spec.dynamic ? env.choices(r.key) : r.spec.choices;
    }

    /** How many values a chooser or switch steps through (0 for the others). */
    public int count(Row r) {
        switch (r.kind) {
            case BOOL: return 2;
            case CHOICE: return choicesOf(r).size();
            default: return 0;
        }
    }

    public boolean boolValue(Row r) {
        return "1".equals(store.get(r.key));
    }

    /** The current number of a numeric row (a range, or a chooser of numbers). */
    public int intValue(Row r) {
        try {
            return Integer.parseInt(store.get(r.key).trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** The current value string of a row. */
    public String value(Row r) {
        String v = store.get(r.key);
        return v == null ? "" : v;
    }

    /** True when this restart-only setting differs from the running game's (the row's badge). */
    public boolean restartPending(Row r) { return r.restart && env.restartPending(r.key); }

    /** The text of the value on the right of a row: a literal, or "@name" for a string resource. Never empty. */
    public String valueText(Row r) {
        String ext = env.external(r.key);
        if (ext != null && !ext.isEmpty()) return ext;
        switch (r.kind) {
            case BOOL: return boolValue(r) ? "@on" : "@off";
            case ACTION: return "@cap_" + r.spec.caption;
            case RANGE: return intValue(r) + r.spec.unit;
            case CHOICE: {
                List<OptionSpec.Choice> cs = choicesOf(r);
                int at = choiceIndex(r);
                if (at >= 0 && at < cs.size() && !cs.get(at).token.isEmpty()) return cs.get(at).token;
                return "@choose";
            }
            default: return "@choose";
        }
    }

    /**
     * The value tokens of a chooser in the order step() walks them ("@name" or a literal, as valueText),
     * or an empty list when the row is not a chooser. The view draws a short list as a segmented control and
     * picks one by stepping (choiceIndex() to the tapped one).
     */
    public List<String> choiceTokens(Row r) {
        List<String> out = new ArrayList<>();
        if (r.kind != Kind.CHOICE || r.spec.dynamic) return out;
        for (OptionSpec.Choice c : r.spec.choices) out.add(c.token);
        return out;
    }

    /** The index in choices of the current value (what step() starts from), 0 when unknown. */
    public int choiceIndex(Row r) {
        if (r.kind != Kind.CHOICE) return 0;
        String cur = value(r);
        List<OptionSpec.Choice> cs = choicesOf(r);
        for (int i = 0; i < cs.size(); ++i) if (cs.get(i).value.equals(cur)) return i;
        return 0;
    }

    /** True when the row cannot be used (its value cannot change): a chooser with one value, an info line, a muted row. */
    public boolean disabled(Row r) {
        return disabledReason(r) != null;
    }

    /** Why a muted row cannot be used, as a string resource name ("" = nothing to say): null when it can. */
    public String disabledReason(Row r) {
        if (r.kind == Kind.INFO) return "";
        return env.disabledReason(r.key);
    }

    // ---- Changes ----

    /** Steps a chooser by d (wrapping), flips a switch or moves a range by d steps; true when something changed. */
    public boolean step(Row r, int d) {
        if (r.kind == Kind.ACTION || r.kind == Kind.INFO || disabled(r)) return false;
        if (r.kind == Kind.RANGE) return setRange(r, intValue(r) + d * r.step);
        if (r.kind == Kind.BOOL) {
            store.set(r.key, boolValue(r) ? "0" : "1");
            return true;
        }
        List<OptionSpec.Choice> cs = choicesOf(r);
        if (cs.isEmpty()) return false;
        int n = cs.size(), at = choiceIndex(r);
        store.set(r.key, cs.get((at + d % n + n) % n).value);
        return true;
    }

    /** Sets a RANGE row (clamped and snapped to its step); true when the value changed. */
    public boolean setRange(Row r, int value) {
        if (r.kind != Kind.RANGE) return false;
        int v = Math.max(r.min, Math.min(r.max, Math.round(value / (float) r.step) * r.step));
        if (v == intValue(r)) return false;
        store.set(r.key, String.valueOf(v));
        return true;
    }

    /** "Restore defaults" of a tab: the game's own reset, else every row of the tab back to its default. */
    public void resetTab(String tabKey) {
        if (store.resetTab(tabKey)) return;
        for (Tab t : tabs) {
            if (!t.key.equals(tabKey)) continue;
            for (Row r : t.rows) if (r.kind != Kind.ACTION && r.kind != Kind.INFO && !r.spec.def.isEmpty()) store.set(r.key, r.spec.def);
        }
    }

    // ---- Navigation ----

    public void selectTab(int t) {
        tab = Math.max(0, Math.min(tabs.size() - 1, t));
        row = 0;
    }

    /** A tap on a row: the focus moves to the rows. */
    public void selectRow(int t, int r) {
        selectTab(t);
        row = Math.max(0, Math.min(tabs.get(tab).rows.size() - 1, r));
        inRows = true;
    }

    /** The tab list focused, on the first tab (the panel just opened). */
    public void reset() {
        tab = 0;
        row = 0;
        inRows = false;
        lastRow = null;
    }

    /** One pad / key press. SELECT on a chooser steps it (+1); on an action it reports ACTION. */
    public Outcome nav(Nav n) {
        lastRow = null;
        int rows = tabs.get(tab).rows.size();
        switch (n) {
            case PREV_TAB:
            case NEXT_TAB:
                selectTab((tab + (n == Nav.PREV_TAB ? -1 : 1) + tabs.size()) % tabs.size());
                return Outcome.MOVED;
            case UP:
            case DOWN: {
                int d = n == Nav.UP ? -1 : 1;
                if (inRows) row = (row + d + rows) % rows;
                else selectTab((tab + d + tabs.size()) % tabs.size());
                return Outcome.MOVED;
            }
            case LEFT:
            case RIGHT:
                if (!inRows) {
                    if (n == Nav.RIGHT) { inRows = true; return Outcome.MOVED; }
                    return Outcome.NONE;
                }
                return stepSelected(n == Nav.LEFT ? -1 : 1);
            case SELECT:
                if (!inRows) { inRows = true; return Outcome.MOVED; }
                return stepSelected(1);
            default:  // BACK: up one level, then close
                if (inRows) { inRows = false; return Outcome.MOVED; }
                return Outcome.CLOSE;
        }
    }

    /** Steps (or fires) the selected row: what a tap on its arrows or value does. */
    public Outcome stepSelected(int d) {
        Row r = selectedRow();
        lastRow = r;
        if (r.kind == Kind.INFO) return Outcome.NONE;
        if (r.kind == Kind.ACTION) return d > 0 ? Outcome.ACTION : Outcome.NONE;  // a muted one too: the panel says why
        return step(r, d) ? Outcome.CHANGED : Outcome.NONE;
    }
}