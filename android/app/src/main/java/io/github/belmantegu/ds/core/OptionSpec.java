package io.github.belmantegu.ds.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One option of the Options screen, as data (pure Java). A game declares its options with these (and
 * TabSpec); the generic OptionsEngine and the OptionsPanel draw and drive them. The kind says the
 * control: a switch (BOOL), a chooser (CHOICE: a fixed list of values, or one the environment supplies),
 * a slider (RANGE), an action button (ACTION) or a read-only line (INFO). Values are strings in the
 * ValueStore ("1" / "0" for a switch). Texts are string-resource names (label, sub) looked up by the app.
 */
public final class OptionSpec {
    /** A value of a chooser and the token that shows it: "@name" (a string resource) or a literal. */
    public static final class Choice {
        public final String value, token;

        public Choice(String value, String token) {
            this.value = value;
            this.token = token;
        }
    }

    /** A card of the "cards" control: the icon, the title token and the second line (a token with "@names" allowed). */
    public static final class Card {
        public final String icon, sub;

        public Card(String icon, String sub) {
            this.icon = icon;
            this.sub = sub;
        }
    }

    public final String key;
    public final OptionsEngine.Kind kind;
    public boolean restart, experimental, dynamic;
    public int min, max, step = 1;
    public String unit = "", icon = "", def = "", label, sub, when = "", caption = "open", control = "";
    public List<Choice> choices = Collections.emptyList();
    public List<Card> cards = Collections.emptyList();

    private OptionSpec(String key, OptionsEngine.Kind kind) {
        this.key = key;
        this.kind = kind;
        this.label = "opt_" + key;
        this.sub = "opt_" + key + "_sub";
    }

    public static OptionSpec bool(String key) { return new OptionSpec(key, OptionsEngine.Kind.BOOL).def("1"); }

    public static OptionSpec choice(String key, Choice... choices) {
        OptionSpec s = new OptionSpec(key, OptionsEngine.Kind.CHOICE);
        s.choices = new ArrayList<>(Arrays.asList(choices));
        if (choices.length > 0) s.def = choices[0].value;
        return s;
    }

    public static OptionSpec range(String key, int min, int max, int step, String unit) {
        OptionSpec s = new OptionSpec(key, OptionsEngine.Kind.RANGE);
        s.min = min;
        s.max = max;
        s.step = step;
        s.unit = unit;
        return s;
    }

    public static OptionSpec action(String key, String icon, String caption) {
        OptionSpec s = new OptionSpec(key, OptionsEngine.Kind.ACTION);
        s.icon = icon;
        s.caption = caption;
        return s;
    }

    public static OptionSpec info(String key) { return new OptionSpec(key, OptionsEngine.Kind.INFO); }

    public static Choice c(String value, String token) { return new Choice(value, token); }

    public OptionSpec restart() { restart = true; return this; }

    public OptionSpec experimental() { experimental = true; return this; }

    /** The visibility flag (OptionsEnv.flag): the row is there only when it is true. "" = always. */
    public OptionSpec when(String flag) { when = flag; return this; }

    public OptionSpec def(String value) { def = value; return this; }

    public OptionSpec icon(String name) { icon = name; return this; }

    /** The string-resource names of the label and the description (default "opt_" + key and "_sub"). */
    public OptionSpec texts(String labelRes, String subRes) { label = labelRes; sub = subRes; return this; }

    /** The chooser's values come from OptionsEnv.choices(key) (imported drivers). */
    public OptionSpec dynamic() { dynamic = true; return this; }

    /** Draw the chooser as cards (the profile row): one card per choice with this icon and second line. */
    public OptionSpec cards(Card... c) { control = "cards"; cards = new ArrayList<>(Arrays.asList(c)); return this; }

    public OptionSpec select() { control = "select"; return this; }
}