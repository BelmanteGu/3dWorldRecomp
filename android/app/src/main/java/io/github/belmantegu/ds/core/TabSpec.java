package io.github.belmantegu.ds.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A tab of the Options screen: its key, icon, texts (string-resource names), an optional warning and its rows (pure Java). */
public final class TabSpec {
    public final String key, icon;
    public String title, sub, warning = "";
    public final List<OptionSpec> rows;

    public TabSpec(String key, String icon, OptionSpec... rows) {
        this.key = key;
        this.icon = icon;
        this.title = "tab_" + key;
        this.sub = "tab_" + key + "_sub";
        this.rows = new ArrayList<>(Arrays.asList(rows));
    }

    /** A warning shown above the rows (the string-resource name). */
    public TabSpec warning(String res) { warning = res; return this; }

    public TabSpec add(OptionSpec s) { rows.add(s); return this; }
}