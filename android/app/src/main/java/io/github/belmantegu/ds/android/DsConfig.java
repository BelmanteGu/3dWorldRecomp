package io.github.belmantegu.ds.android;

import android.content.Context;
import android.graphics.Typeface;

import io.github.belmantegu.ds.core.GameTheme;

/**
 * What the design system needs to know about the game, once: its theme and where its two typefaces come
 * from (the app owns the font resources). Installed by the game's activities before they build any
 * design-system view (DsConfig.install); every DS class reads the theme from here or from the DsKit it is
 * given, never from the game.
 */
public final class DsConfig {
    private DsConfig() {}

    /** Loads the title and body typefaces (the app's font resources). */
    public interface Fonts {
        Typeface title(Context context);

        Typeface body(Context context);
    }

    private static GameTheme theme = GameTheme.diablo();
    private static Fonts fonts;

    public static void install(GameTheme gameTheme, Fonts gameFonts) {
        theme = gameTheme;
        fonts = gameFonts;
    }

    public static GameTheme theme() { return theme; }

    public static Typeface title(Context c) { return fonts != null ? fonts.title(c) : Typeface.DEFAULT_BOLD; }

    public static Typeface body(Context c) { return fonts != null ? fonts.body(c) : Typeface.DEFAULT; }
}