package io.github.belmantegu.ds.android;

import io.github.belmantegu.ds.core.*;

import android.graphics.Paint;
import android.view.View;

/**
 * Base of the design system's drawn components: the kit, a paint, the "muted" state (drawn at 45%) and a
 * repaint when pressed. A component sizes itself with kit.cq() / kit.touch(); its plate is drawn centred in
 * a view that is at least 48 dp tall, so the touch target is large while the picture follows the reference.
 */
public abstract class DsView extends View {
    public final DsKit k;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean muted;

    public DsView(DsKit kit) {
        super(kit.context);
        k = kit;
        setClickable(true);
        setFocusable(false);  // the pad focus is the model's (rows draw it), not the framework's veil
        setDefaultFocusHighlightEnabled(false);
    }

    public void setMuted(boolean m) {
        if (muted != m) {
            muted = m;
            invalidate();
        }
    }

    public boolean muted() { return muted; }

    /** Colour with the muted state applied. */
    public int shade(int color) { return muted ? DsKit.alpha(color, .45f) : color; }

    @Override
    public void setPressed(boolean pressed) {
        super.setPressed(pressed);
        invalidate();
    }
}
