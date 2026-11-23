package io.github.belmantegu.ds.core;

/**
 * The design system's tokens for one game (pure Java, no android imports, tested on the JVM by
 * GameThemeTest). The structure of the Options screen is the same in every port (Ds* components); a game
 * only changes this block: colours, corner radii and the title style. Radii are in layout units, 1 unit
 * = 1% of the screen width (the reference page's "cqw"), so a rounded corner scales with the screen.
 * Colours are ARGB. See documentation/guides/DESIGN_SYSTEM.md.
 */
public final class GameTheme {
    /**
     * The radius rule (three values per theme): sm for badges, chips, small inputs and a control's inner
     * segments; md for buttons, rows, menu items, cards and notices; lg for dialogs, sheets and panels.
     * A button size maps to it here: S is sm, M and L are md.
     */
    public float radius(DsMetrics.Size size) {
        return size == DsMetrics.S ? rSm : rMd;
    }

    /** Overlays and marks that are the same in every game (translucent blacks and whites, the FPS dot colours). */
    public static final int SCRIM = 0x73000000, SCRIM_DEEP = 0xD0000000, SHADE = 0x80000000, TRACK = 0x66000000, TEXT_SHADOW = 0xAA000000,
            PAD_A = 0xFF3E9A3A, PAD_B = 0xFFC8321E, WHITE = 0xFFFFFFFF, GLINT = 0x38FFFFFF, PRESS = 0x22FFFFFF, CAUTION = 0xFFFFD34D, CRITICAL = 0xFFFF5A5F;
    public final String id, brand;
    /** Screen, side column, card / control fill, selected / raised fill, hairline between rows. */
    public final int bg, side, surface, surface2, line;
    /** Text, secondary text, the one live colour (selected tab, switch, slider, selected segment) and the text on it. */
    public final int fg, dim, accent, accentFg;
    /** The pad / keyboard focus ring, a notice (restart badge, warnings) and a risky option. */
    public final int focus, warn, danger;
    /** Corner radii (small, medium, large) in layout units. */
    public final float rSm, rMd, rLg;
    /** The selected menu item's gradient (left, middle, right), the bar at its left edge (0 = none) and the ready colour. */
    public final int selA, selB, selC, bar, ok;
    /** The title and brand are set in capitals with this letter spacing (em). */
    public final boolean upperTitle;
    public final float titleSpacing;

    GameTheme(String id, String brand, int bg, int side, int surface, int surface2, int line, int fg, int dim,
                      int accent, int accentFg, int focus, int warn, int danger, float rSm, float rMd, float rLg,
                      boolean upperTitle, float titleSpacing, int selA, int selB, int selC, int bar, int ok) {
        this.id = id;
        this.brand = brand;
        this.bg = bg;
        this.side = side;
        this.surface = surface;
        this.surface2 = surface2;
        this.line = line;
        this.fg = fg;
        this.dim = dim;
        this.accent = accent;
        this.accentFg = accentFg;
        this.focus = focus;
        this.warn = warn;
        this.danger = danger;
        this.rSm = rSm;
        this.rMd = rMd;
        this.rLg = rLg;
        this.upperTitle = upperTitle;
        this.titleSpacing = titleSpacing;
        this.selA = selA;
        this.selB = selB;
        this.selC = selC;
        this.bar = bar;
        this.ok = ok;
    }

    /** Marvel vs. Capcom 3: warm black and red, nearly square corners, Anton titles. */
    private static final GameTheme DMC = new GameTheme("dmc", "Projeto Belmante",
            0xFF0C0A0B, 0xFF120E10, 0xFF1B1517, 0xFF271C1F, 0xFF2C2124,
            0xFFF3EEEE, 0xFF9B8F91, 0xFFE0262F, 0xFFFFFFFF,
            0xFF5AA7FF, 0xFFFF9A3C, 0xFFFF6A4C, .25f, .5f, .8f, true, .04f,
            0xFF8E1018, 0xFFE0262F, 0xFFB3171F, 0xFF5AA7FF, 0xFF6BD68A);

    /** Ultra Street Fighter IV: graphite and orange, medium corners, Bebas Neue titles. */
    private static final GameTheme STREET = new GameTheme("street", "Ultra Street Fighter IV",
            0xFF0E0E11, 0xFF131317, 0xFF1C1C22, 0xFF2A2A32, 0xFF2B2B33,
            0xFFF6F3EE, 0xFFA5A1A0, 0xFFFF9F1A, 0xFF1A0D00,
            0xFFFFE27A, 0xFFFF7A3C, 0xFFFF5A3C, .5f, .9f, 1.3f, true, .05f,
            0xFFFF5A00, 0xFFFF9F1A, 0xFFFFC24D, 0, 0xFF7EE06A);

    /**
     * Super Mario 3D World: a night-blue ground, the cap's red as the live colour (selected tab, switch,
     * segment) and the selected item's gradient (red into orange), sky blue for the pad focus, coin yellow
     * for notices; very round corners and Baloo 2 titles in mixed case (the Rayman family's shape).
     */
    private static final GameTheme MARIO = new GameTheme("mario", "Super Mario 3D World",
            0xFF121729, 0xFF0E1222, 0xFF1C2340, 0xFF283058, 0xFF2C3556,
            0xFFF2F4FA, 0xFFA5ACC6, 0xFFE8382E, 0xFFFFFFFF,
            0xFF35B9F0, 0xFFFFC633, 0xFFFF5A4C, .8f, 1.4f, 2.2f, false, 0f,
            0xFFC62A22, 0xFFE8382E, 0xFFF27A3A, 0, 0xFF5BD06A);

    /** Rayman Origins: night blue and gold, very round corners, Baloo 2 titles in mixed case. */
    private static final GameTheme RAYMAN = new GameTheme("rayman", "Rayman Origins",
            0xFF0D1118, 0xFF111722, 0xFF182130, 0xFF233044, 0xFF26324A,
            0xFFF2F6FF, 0xFF93A1BA, 0xFFFFC83D, 0xFF2A1D00,
            0xFF7CC4FF, 0xFFFF8A5B, 0xFFFF6A4C, .8f, 1.4f, 2.2f, false, 0f,
            0xFFFF3D8B, 0xFFFF9A1F, 0xFFFFD02E, 0, 0xFF6FF0A8);

    /**
     * Diablo III: Reaper of Souls: the native title screen's teal-black ground, ice-teal for focus and the
     * ready state, the logo's pale gold for the selected item and the accent, ember and Blizzard red for
     * notices and risks. Corner radii and title spacing are the DMC family's (DMC above): the home and the
     * options keep the same metrics as the MVC3 / DMC ports, only the colours change.
     */
    private static final GameTheme DIABLO = new GameTheme("diablo", "Diablo III",
            0xFF061613, 0xFF091D1A, 0xFF0F2522, 0xFF183533, 0xFF21403C,
            0xFFE9FAFA, 0xFF8FB3B1, 0xFFF3E6D0, 0xFF061613,
            0xFF4CFDFE, 0xFFE8A54A, 0xFFFF6A55, .25f, .5f, .8f, true, .04f,
            0xFFC9B48C, 0xFFF3E6D0, 0xFFE6D3AE, 0xFF4CFDFE, 0xFF4CFDFE);

    /** The DMC family's original theme (Marvel vs. Capcom 3's red and black). */
    public static GameTheme dmc() { return DMC; }

    /** Diablo III's theme: the look of this port. */
    public static GameTheme diablo() { return DIABLO; }

    /** Ultra Street Fighter IV's theme. */
    public static GameTheme street() { return STREET; }

    /** Rayman Origins' theme. */
    public static GameTheme rayman() { return RAYMAN; }
    public static GameTheme mario() { return MARIO; }

    public static final GameTheme[] ALL = {DMC, STREET, RAYMAN, DIABLO, MARIO};

    public static GameTheme byId(String id) {
        for (GameTheme t : ALL) if (t.id.equals(id)) return t;
        return DMC;
    }
}