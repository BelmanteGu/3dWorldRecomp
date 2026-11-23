package io.github.belmantegu.ds.android;

import io.github.belmantegu.ds.core.*;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * What every design-system component shares: the game's tokens (GameTheme), the layout unit `u`
 * (1% of the 100-unit page, the reference page's "cqw": min(width / 100, height / 46.15) of the safe area), the two typefaces and a few drawing helpers.
 * One instance per screen; the components keep a reference and read sizes through cq() / touch().
 */
public final class DsKit {
    /** The platform's smallest touch target, in dp. */
    public static final int TOUCH_DP = DsMetrics.TOUCH_DP;

    public final Context context;
    public final GameTheme t;
    public final float u, density;
    /** Pixels from each window edge to the stage (HomeUi.gap); zero when the kit was not made from a HomeUi. */
    public final android.graphics.Rect gap = new android.graphics.Rect();
    public final Typeface title, body;
    private final RectF box = new RectF();

    public DsKit(Context context, GameTheme theme, float unit, Typeface title, Typeface body) {
        this.context = context;
        this.t = theme;
        this.u = unit;
        this.title = title;
        this.body = body;
        this.density = context.getResources().getDisplayMetrics().density;
    }

    /** The kit of a screen: the theme, unit and typefaces its HomeUi carries. */
    public static DsKit of(HomeUi ui) {
        DsKit k = new DsKit(ui.activity, ui.theme, ui.u, ui.display, ui.ui);
        k.gap.set(ui.gap);
        return k;
    }

    /** Layout units to pixels. */
    public float cq(float units) { return units * u; }

    /** A size in layout units, but never under 48 dp (a touch target). */
    public int touch(float units) { return Math.max(Math.round(units * u), Math.round(TOUCH_DP * density)); }

    /** A rounded rectangle of radius `r` layout units, clamped to a pill. */
    public void rect(Canvas c, float l, float tp, float r, float b, float radiusUnits, Paint p) {
        float rad = Math.min(cq(radiusUnits), Math.min(r - l, b - tp) / 2f);
        box.set(l, tp, r, b);
        c.drawRoundRect(box, rad, rad, p);
    }

    /** y of the baseline that centres the text in `p` on `cy`. */
    public static float baseline(Paint p, float cy) {
        return cy - (p.descent() + p.ascent()) / 2f;
    }

    /** A text paint: face, size in layout units, colour. */
    public Paint text(Paint p, Typeface face, float sizeUnits, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(face);
        p.setTextSize(cq(sizeUnits));
        p.setColor(color);
        return p;
    }

    /** A TextView in the body face (no font padding, so the line boxes match the reference). */
    public TextView label(String s, float sizeUnits, int color) {
        TextView v = new TextView(context);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, cq(sizeUnits));
        v.setTextColor(color);
        v.setTypeface(body);
        v.setIncludeFontPadding(false);
        return v;
    }

    /** A title-face TextView: capitals and letter spacing as the theme says. */
    public TextView titleLabel(String s, float sizeUnits, int color) {
        TextView v = label(t.upperTitle ? s.toUpperCase(java.util.Locale.ROOT) : s, sizeUnits, color);
        v.setTypeface(title);
        v.setLetterSpacing(t.titleSpacing);
        return v;
    }

    /** `color` with its alpha replaced (a disabled control is drawn at 45%). */
    public static int alpha(int color, float a) {
        return ((int) (255 * a) << 24) | ((color << 8) >>> 8);
    }
}
