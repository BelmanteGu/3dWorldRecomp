package io.github.belmantegu.ds.android;

import android.content.Context;

import io.github.belmantegu.ds.core.TextBundle;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Texts by name. The design system's own strings are prefixed ds_ (ds_strings.xml); a game's are its
 * own. A token is a literal ("75%") or has "@name" parts: each is looked up as ds_<name> first, then as
 * <name> (the game's), so a game may add or override a value word ("@val_auto").
 */
public final class DsText {
    private DsText() {}

    private static final Pattern PART = Pattern.compile("@([A-Za-z0-9_]+)");

    /** The string named `name` ("" when there is none). */
    public static String get(Context c, String name) {
        // The game's data first (TextBundle: the texts its JSON manifest declares), then the app's resources.
        TextBundle bundle = TextBundle.active();
        if (bundle.has(name)) return bundle.get(name, tag(c));
        int id = identifier(c, name, "string");
        return id == 0 ? "" : c.getString(id);
    }

    /** The language tag of the app's current locale ("pt-BR", "en-US"). */
    public static String tag(Context c) {
        java.util.Locale l = c.getResources().getConfiguration().getLocales().get(0);
        return l.getLanguage() + (l.getCountry().isEmpty() ? "" : "-" + l.getCountry());
    }

    /** A token as text: "@name" parts replaced, everything else as it is. */
    public static String token(Context c, String token) {
        if (token == null || token.indexOf('@') < 0) return token == null ? "" : token;
        Matcher m = PART.matcher(token);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String s = get(c, "ds_" + m.group(1));
            if (s.isEmpty()) s = get(c, m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(s));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** A style named `name` (0 when there is none). */
    public static int style(Context c, String name) {
        return identifier(c, name, "style");
    }

    /** The resource id by name: the installed package first, then the app's resource package (a build whose
     *  application id differs from its namespace, as the Super Mario 3D World build of the WWHD app). */
    static int identifier(Context c, String name, String type) {
        int id = c.getResources().getIdentifier(name, type, c.getPackageName());
        return id != 0 ? id : c.getResources().getIdentifier(name, type, "org.wwhdrecomp.wwhd");
    }
}