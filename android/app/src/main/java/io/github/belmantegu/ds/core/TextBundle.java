package io.github.belmantegu.ds.core;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Texts by name and language, the ones a game declares in its data (pure Java). DsText asks it first and
 * falls back to the app's string resources, so a game can keep its Options texts in the manifest instead
 * of strings.xml. A language tag is looked up as given ("pt-br"), then by its language ("pt"), then "en".
 */
public final class TextBundle {
    private final Map<String, Map<String, String>> byName = new HashMap<>();

    private static String norm(String tag) {
        return tag == null ? "en" : tag.replace('_', '-').toLowerCase(Locale.ROOT);
    }

    /** Adds a text; "pt-BR" is also the plain "pt" when no other Portuguese is given. */
    public void put(String name, String lang, String text) {
        Map<String, String> m = byName.get(name);
        if (m == null) byName.put(name, m = new HashMap<>());
        String l = norm(lang);
        m.put(l, text);
        int dash = l.indexOf('-');
        if (dash > 0 && !m.containsKey(l.substring(0, dash))) m.put(l.substring(0, dash), text);
    }

    public boolean has(String name) { return byName.containsKey(name); }

    /** The text in `tag`'s language, else English, else "". */
    public String get(String name, String tag) {
        Map<String, String> m = byName.get(name);
        if (m == null) return "";
        String l = norm(tag), s = m.get(l);
        if (s == null && l.indexOf('-') > 0) s = m.get(l.substring(0, l.indexOf('-')));
        if (s == null) s = m.get("en");
        return s == null ? "" : s;
    }

    public int size() { return byName.size(); }

    private static volatile TextBundle active = new TextBundle();

    /** The bundle DsText reads (installed once by the game's Application). */
    public static void install(TextBundle bundle) { active = bundle == null ? new TextBundle() : bundle; }

    public static TextBundle active() { return active; }
}
