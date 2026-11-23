package io.github.belmantegu.ds.android;

import io.github.belmantegu.ds.core.*;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;

/**
 * The home screen's shared helpers (HomeScreen, SheetMenu and GameMenu lay the views out): the layout
 * unit, the two typefaces (Anton and Barlow Semi Condensed, both OFL, android/licenses), text and placement
 * helpers, the pad-button hints, the design-system plate and the import progress line.
 */
public final class HomeUi {
    public final Activity activity;
    public final Typeface display, ui;
    public final GameTheme theme;
    public final float u;  // layout unit: min(width / 100, height / 46.15) of the safe area
    /** The window in pixels (landscape order) and the stage: the 100-unit-wide page, centred in the safe area. */
    public final int screenW, screenH, stageW, stageH;
    /** Pixels from each window edge to the stage: the display cutout and the bars, plus the centring when the screen is wider than the page. */
    public final Rect gap = new Rect();

    public HomeUi(Activity activity) {
        this.activity = activity;
        theme = DsConfig.theme();
        display = DsConfig.title(activity);
        ui = DsConfig.body(activity);
        android.util.DisplayMetrics m = activity.getResources().getDisplayMetrics();
        int w = Math.max(m.widthPixels, m.heightPixels), h = Math.min(m.widthPixels, m.heightPixels);
        int il = 0, it = 0, ir = 0, ib = 0;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            // the real safe area: the display cutout (the camera hole). The screens are immersive, so the status and the
            // navigation bars are hidden and must not shrink the stage. Kept symmetric: the sensor-landscape rotation
            // flips the camera hole to the other side without recreating the activity.
            android.view.WindowMetrics wm = activity.getWindowManager().getCurrentWindowMetrics();
            Rect b = wm.getBounds();
            if (b.width() >= b.height()) {
                w = b.width();
                h = b.height();
                android.graphics.Insets in = wm.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout());
                il = ir = Math.max(in.left, in.right);
                it = ib = Math.max(in.top, in.bottom);
            }
        }
        screenW = w;
        screenH = h;
        int usableW = Math.max(1, w - il - ir), usableH = Math.max(1, h - it - ib);
        u = Math.min(usableW / DsMetrics.STAGE_W, usableH / DsMetrics.STAGE_H);
        stageW = Math.min(usableW, Math.round(u * DsMetrics.STAGE_W));
        stageH = usableH;
        int side = (usableW - stageW) / 2;
        gap.set(il + side, it, w - (il + side + stageW), ib);
    }

    /** The stage as a view in `parent` (which is the full window): everything of a screen is placed inside it, in units; backgrounds stay in `parent`. */
    public FrameLayout stage(FrameLayout parent) {
        FrameLayout s = new FrameLayout(activity);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(stageW, -1);
        lp.leftMargin = gap.left;
        lp.topMargin = gap.top;
        lp.bottomMargin = gap.bottom;
        parent.addView(s, lp);
        return s;
    }

    public int px(float units) { return Math.round(units * u); }

    /** A touch target's size: the layout unit, but never under 48 dp (the platform's minimum). */
    public int touch(float units) {
        return Math.max(px(units), Math.round(DsMetrics.TOUCH_DP * activity.getResources().getDisplayMetrics().density));
    }

    /** Top-left placement in layout units; w/h in units, or -1/-2 (match/wrap). */
    public FrameLayout.LayoutParams at(float x, float y, float w, float h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w < 0 ? (int) w : px(w), h < 0 ? (int) h : px(h),
                Gravity.TOP | Gravity.START);
        lp.leftMargin = px(x);
        lp.topMargin = px(y);
        return lp;
    }

    public TextView text(String s, float size, int color, Typeface face) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        t.setShadowLayer(u * DsMetrics.SHADOW, 0, u * DsMetrics.SHADOW_DY, GameTheme.TEXT_SHADOW);
        return t;
    }

    /** A plate of the design system: the theme's surface, translucent, with its large radius (toasts, the editor's bar). */
    public Drawable plate() {
        GameTheme t = theme;
        GradientDrawable d = new GradientDrawable();
        d.setColor(DsKit.alpha(t.surface, .94f));
        d.setCornerRadius(u * t.rLg);
        return d;
    }

    /** A controller button's badge and what it does ("A Select"). */
    public View padHint(String letter, String label, int color) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = text(letter, DsMetrics.T_BADGE, GameTheme.WHITE, ui);
        badge.setGravity(Gravity.CENTER);
        badge.setShadowLayer(0, 0, 0, 0);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color);
        badge.setBackground(circle);
        row.addView(badge, new LinearLayout.LayoutParams(px(DsMetrics.PAD_BADGE), px(DsMetrics.PAD_BADGE)));
        TextView t = text(label, DsMetrics.T_BADGE, theme.fg, ui);
        t.setPadding(px(DsMetrics.PAD_GAP), 0, px(DsMetrics.PAD_RIGHT), 0);
        row.addView(t);
        return row;
    }

    public Bitmap loadAsset(String path) {
        try (InputStream in = activity.getAssets().open(path)) {
            return BitmapFactory.decodeStream(in);
        } catch (IOException e) {
            return null;
        }
    }

    /** A thin progress bar for imports, drawn on the status plate. */
    public final class ProgressLine extends View {
        float value = -1;  // 0..1, or < 0 hidden
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ProgressLine() { super(activity); }

        public void set(float v) {
            value = v;
            setVisibility(v < 0 ? GONE : VISIBLE);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), r = h / 2f;
            paint.setColor(GameTheme.TRACK);
            c.drawRoundRect(0, 0, getWidth(), h, r, r, paint);
            paint.setShader(new LinearGradient(0, 0, 0, h, theme.selB, theme.selA, Shader.TileMode.CLAMP));
            c.drawRoundRect(0, 0, Math.max(h, getWidth() * Math.min(1, value)), h, r, r, paint);
            paint.setShader(null);
        }
    }
}
