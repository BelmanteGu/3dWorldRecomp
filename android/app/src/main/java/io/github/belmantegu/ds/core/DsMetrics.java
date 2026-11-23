package io.github.belmantegu.ds.core;

/**
 * Every measure of the design system in one place (pure Java, no android imports). Names are the same in
 * every game's port (DsMetrics, GameTheme): a game changes GameTheme's colours and radii, never these.
 * Lengths are layout units: 1 unit = 1% of the screen width (the reference page's "cqw"), scaled by
 * DsKit.cq(). Tested by DsMetricsTest; tools/android/check_design.py fails when a view uses a bare number
 * for a size, a literal radius or a literal colour. documentation/guides/DESIGN_SYSTEM.md has the same
 * values as tables and the position map.
 */
public final class DsMetrics {
    private DsMetrics() {}

    // ---- The stage: the unit follows the smaller of the width and the height ----
    /** The reference page is 100 units wide and 46.15 tall (19.5:9, a phone in landscape); u = min(width / 100, height / 46.15). */
    public static final float STAGE_W = 100f, STAGE_H = 46.15f;

    // ---- Spacing scale ----
    /** Inside a control, between controls, between groups, the screen's safe margin, a large break. */
    public static final float S1 = .5f, S2 = 1f, S3 = 1.5f, S4 = 2.5f, S5 = 4f;
    /** The safe margin of a full screen: equal on the four sides. */
    public static final float MARGIN = S4;
    /** Gap between an icon and its caption, in every button and item. */
    public static final float ICON_GAP = 1.2f;
    /** The platform's smallest touch target, in dp. */
    public static final int TOUCH_DP = 48;

    // ---- Button sizes ----
    /** Height of the drawn plate, icon, caption font, horizontal padding, minimum width. */
    public static final class Size {
        public final float h, icon, font, padX, minW;

        public Size(float h, float icon, float font, float padX, float minW) {
            this.h = h;
            this.icon = icon;
            this.font = font;
            this.padX = padX;
            this.minW = minW;
        }
    }

    /** L: a menu item. M: the main action of a card or dialog, the icon-only Back, a row. S: a chip, a row action, a control. */
    public static final Size L = new Size(7.2f, 3f, 2.6f, 2f, 0f), M = new Size(5.2f, 2.4f, 1.8f, 1.8f, 12f), S = new Size(4.2f, 2f, 1.5f, 1.2f, 8f);

    // ---- Type: five steps ----
    /** Title (screen, sheet), item (menu item, dialog title), body (row title, value), description, seal. */
    public static final float T_TITLE = 3f, T_ITEM = L.font, T_BODY = M.font, T_DESC = S.font, T_BADGE = 1.15f;
    public static final float T_LINE = 1.12f;

    // ---- Icons: three sizes ----
    public static final float ICON_S = S.icon, ICON_M = M.icon, ICON_L = L.icon;

    /** Fully round (a switch, a slider track, the "Game paused" pill): a radius larger than any plate, clamped by it. */
    public static final float PILL = 9f;
    /** Stroke widths: the focus ring, the light line inside a selected item, its edge bar. */
    public static final float RING = .25f, ITEM_LINE = .3f, ITEM_BAR = .5f, STROKE = .2f, HAIRLINE = .1f;

    // ---- Positions: one grid ----
    /** The left edge every item of a screen aligns to (kicker, logo, subtitle, menu, sheet title, state, version). */
    public static final float COL_CONTENT = MARGIN;
    /** The four corners' inset: the pad hints (top right), the version (bottom right), the FPS counter (top left), toasts (top centre). */
    public static final float CORNER = MARGIN;
    /** The top line of the home (kicker and pad hints share it). */
    public static final float LINE_TOP = MARGIN - .3f;
    /** The home's right-hand column (state bar and card): after the menu and a large break. */
    public static final float CARD_W_STATE = 44f, MENU_W = 26f, COL_STATE = COL_CONTENT + MENU_W + S5;
    /** The home's vertical order: kicker, logo, subtitle, menu, state. */
    public static final float LOGO_W = 13f, LOGO_TOP = 4f, SUBTITLE_H = 1.6f;
    /** The home's split layout (menu on the right): the margin of the page, the left column's width, its top and the gap between its parts, the menu and its item height, the menu's room above and below. */
    public static final float SPLIT_MARGIN = 4f, SPLIT_COL_W = 50f, SPLIT_COL_TOP = 5f, SPLIT_BOTTOM = 2.2f, SPLIT_GAP = 1.2f,
            SPLIT_MENU_W = 28f, SPLIT_ITEM_H = 6.2f, SPLIT_MENU_TOP = 5f, SPLIT_MENU_BOTTOM = 8f, SPLIT_LOGO_MIN = 9f, SPLIT_COL_GAP = 3f;
    /** The in-game sheet's width (a share of the screen). */
    public static final float SHEET_W = 31f;
    /** Options: the side column, the label column of a row and the control column that follows. */
    public static final float NAV_W = 17f, ROW_LABEL_W = 31f, ROW_CONTROL_GAP = 2f;

    // ---- Controls (one height: the S size) ----
    public static final float CONTROL_H = S.h;
    public static final float TOGGLE_W = 5.4f, TOGGLE_H = 3f, TOGGLE_KNOB = 2.3f, TOGGLE_INSET = .35f;
    public static final float SELECT_MIN_W = 16f, SELECT_PAD = 1.2f, SELECT_ARROW_GAP = 1.6f, SELECT_ARROW = ICON_S, SELECT_LEAD = ICON_S, SELECT_LEAD_GAP = .9f;
    public static final float SEG_PAD = 1.4f, SEG_INSET = .3f, SEG_GAP = .2f, SEG_HIT = .1f;
    public static final float SLIDER_TRACK_W = 28f, SLIDER_TRACK_H = .9f, SLIDER_KNOB = 2.2f, SLIDER_CHIP_H = 3f, SLIDER_PAD = 1.1f,
            SLIDER_CHIP_GAP = 1.2f, SLIDER_CHIP_PAD = 1f, SLIDER_MIN = 4f;

    // ---- Choice cards ----
    public static final float CARD_W = 40f, CARD_GAP = 1f, CARD_PAD = 1.1f, CARD_TOP = 1f, CARD_ICON = ICON_M, CARD_ICON_GAP = .3f,
            CARD_LINE = 1.25f, CARD_CHECK = ICON_S, CARD_CHECK_INSET = .9f;

    // ---- Rows and the side column ----
    public static final float ROW_MIN_H = M.h, ROW_PAD_X = 1.2f, ROW_PAD_Y = 1.05f, ROW_LABEL_MIN = 14f, ROW_STACK_AT = 30f,
            ROW_BADGE_GAP = .8f, ROW_BADGE_LINE_GAP = .2f, ROW_DESC_GAP = .25f;
    public static final float NAV_PAD_X = 1.2f, NAV_PAD_Y = 1.6f, NAV_ITEM_H = S.h, NAV_ITEM_FRAC = .072f, NAV_BRAND_PAD = .8f,
            NAV_LIST_GAP = 1.2f, NAV_VER_PAD = .9f, NAV_INSET = .2f, NAV_PAD = .9f, NAV_TEXT_GAP = 1.1f, NAV_RIGHT = .4f;

    // ---- Options screen ----
    public static final float MAIN_PAD_L = 1.8f, MAIN_PAD_R = 2f, HEAD_PAD_X = 1.2f, HEAD_PAD_T = 1.4f, HEAD_PAD_B = .8f, HEAD_SUB_GAP = .35f,
            HEAD_TAB_GAP = 1.4f, BANNER_W = 26f, WARN_PAD = 1.2f;

    // ---- Badges ----
    public static final float BADGE_H = 2.6f, BADGE_ICON = ICON_S, BADGE_GAP = .3f, BADGE_SPACING = .05f;

    // ---- Notices (the one state component) and toasts ----
    public static final float NOTICE_MAX_W = 60f, NOTICE_PAD_X = S3, NOTICE_PAD_Y = S2, NOTICE_ICON = ICON_M, NOTICE_MIN_H = M.h;

    // ---- Dialogs ----
    public static final float DIALOG_W_S = 40f, DIALOG_W_M = 56f, DIALOG_PAD = S4, DIALOG_ROW_LABEL_W = 13f, DIALOG_PROGRESS_H = .9f,
            DIALOG_MSG_TOP = .3f, DIALOG_WELL_X = .8f, DIALOG_WELL_Y = .6f, DIALOG_TEXT_PAD = .4f, DIALOG_NOTE_PAD = .5f, DIALOG_MIN_SCROLL = 6f,
            DIALOG_SCROLL_LEAD = 3f, DIALOG_STEP = 6f, DIALOG_WIDE_FRAC = .84f, DIALOG_MAX_FRAC = .9f, DIALOG_HEIGHT_FRAC = .92f;

    // ---- In-game sheet and the pad-button hints ----
    public static final float PAD_BADGE = 1.7f, PAD_GAP = .4f, PAD_RIGHT = 1.2f, PAUSE_DOT = 1f, PAUSE_PILL_GAP = .9f, PROGRESS_H = .6f, SHADOW = .3f, SHADOW_DY = .1f, ELEVATION = 4f, EDITOR_W = 92f;

    // ---- FPS counter ----
    public static final float FPS_PILL_H = 3.6f, FPS_PAD = 1.1f, FPS_DOT = .45f, FPS_DOT_GAP = .8f, FPS_UNIT_GAP = .6f, FPS_ROW_H = 2.45f,
            FPS_CARD_PAD = 1.2f, FPS_CARD_TOP = .9f, FPS_GRAPH_H = 2.8f, FPS_GRAPH_GAP = .5f, FPS_BAR_GAP = .15f, FPS_BAR_MIN = .2f,
            FPS_CARD_MIN_W = 15f, FPS_LABEL_GAP = 1.6f, FPS_LABEL_INDENT = 1.5f, FPS_SLOP = .8f;

    // ---- Statistics screen: a tile's number, the session graph, the header ----
    public static final float STATS_VALUE = 3.2f, STATS_GRAPH_H = 8f, STATS_HEAD_H = 7.4f, STATS_LEFT_W = 52f;
}